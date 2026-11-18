package com.finsight.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers unauthenticated requests to protected endpoints with HTTP 401 and an RFC 7807 body (J-7).
 *
 * <p>Without this entry point Spring Security falls back to 403, which the frontend's
 * single-flight refresh (AC-SF-1..3) does not treat as an expired session.
 *
 * <p>The body is a fixed constant. It never includes request data and never says why
 * authentication failed (missing, malformed and expired tokens look the same), so it
 * leaks nothing. Authenticated users who lack permission still get 403 from Spring
 * Security's default access-denied handling; this class does not affect that path.
 */
public final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String PROBLEM_JSON = "application/problem+json";

    static final String BODY = """
            {"type":"https://finsight.com/errors/unauthorized",\
            "title":"Unauthorized",\
            "status":401,\
            "detail":"Authentication required"}""";

    /**
     * Writes a 401 response with a {@code WWW-Authenticate: Bearer} header (RFC 6750)
     * and a ProblemDetail JSON body.
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
