package com.finsight.shared.advice;

import com.finsight.shared.exception.AuthenticationFailedException;
import com.finsight.shared.exception.InvalidTokenException;
import com.finsight.shared.exception.NotFoundException;
import com.finsight.shared.exception.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

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
}
