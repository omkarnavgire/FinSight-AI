package com.finsight.shared.advice;

import com.finsight.shared.exception.AuthenticationFailedException;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.ConflictException;
import com.finsight.shared.exception.InvalidTokenException;
import com.finsight.shared.exception.NotFoundException;
import com.finsight.shared.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * Global exception handler mapping domain exceptions to RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>All exception handlers follow these rules:
 * <ul>
 *   <li>Sensitive fields (token values, passwords) are never included in the response body.</li>
 *   <li>Not-found and cross-user access both return 404 to avoid leaking resource existence.</li>
 *   <li>Exception message text is included only where it is safe (e.g. validation errors).</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles not-found and cross-user access scenarios — both return 404.
     *
     * <p>Returning 403 on a cross-user access would reveal that the resource
     * exists but belongs to someone else. 404 avoids leaking that information.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            NotFoundException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://finsight.com/errors/not-found"));
        problem.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Handles business-rule validation failures (e.g. unknown country code) — returns 400.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(
            BadRequestException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://finsight.com/errors/bad-request"));
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Handles state conflicts (e.g. writing to a deactivated account) — returns 409.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(
            ConflictException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://finsight.com/errors/conflict"));
        problem.setTitle("Conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Handles invalid or revoked refresh tokens — returns 401.
     *
     * <p>The detail is intentionally generic; the exception message is not forwarded
     * to prevent leaking token state to a potential attacker.
     */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(
            InvalidTokenException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication required");
        problem.setType(URI.create("https://finsight.com/errors/invalid-token"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Handles failed login attempts — returns 401 with a deliberately vague message.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthFailed(
            AuthenticationFailedException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid credentials");
        problem.setType(URI.create("https://finsight.com/errors/authentication-failed"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Handles rate-limit violations — returns 429 with a {@code Retry-After} header.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RateLimitExceededException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests — please slow down");
        problem.setType(URI.create("https://finsight.com/errors/rate-limit-exceeded"));
        problem.setTitle("Too Many Requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * Handles Bean Validation failures — returns 400 with field-level detail.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        var problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://finsight.com/errors/validation-error"));
        problem.setTitle("Validation Failed");
        problem.setDetail("One or more fields are invalid");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    // ---- T-59: framework errors and a safe fallback (Phase 10) ----

    /**
     * A path or query parameter of the wrong type (e.g. {@code /budgets/abc/progress}) — returns 400.
     * Names the parameter, never echoes the submitted value.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad Request",
                "Parameter '" + ex.getName() + "' has an invalid value");
    }

    /** A missing or malformed JSON body — returns 400 without parser details. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad Request", "Request body is missing or malformed");
    }

    /** Authenticated but not permitted — returns 403 (kept as 403; never turned into a 500). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Access denied");
    }

    /** Authentication failure raised inside a controller — returns 401 with a generic message. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleSpringAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "authentication-failed", "Unauthorized", "Authentication required");
    }

    /**
     * Last resort. Spring MVC exceptions that carry their own status ({@link ErrorResponse}: unknown
     * route 404, wrong method 405, unsupported media type 415, missing parameter 400,
     * {@code ResponseStatusException}, ...) keep that status. Anything else is a 500 with a fixed
     * message; the cause is logged, never returned.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            var status = errorResponse.getStatusCode();
            var problem = ProblemDetail.forStatus(status);
            problem.setTitle(status instanceof HttpStatus known ? known.getReasonPhrase() : "Error");
            problem.setDetail(errorResponse.getBody().getDetail());
            return ResponseEntity.status(status).headers(errorResponse.getHeaders()).body(problem);
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://finsight.com/errors/" + type));
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
