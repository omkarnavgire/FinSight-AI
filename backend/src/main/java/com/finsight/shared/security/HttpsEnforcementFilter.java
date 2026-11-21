package com.finsight.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sends every plain-HTTP request to HTTPS with {@code 308 Permanent Redirect} (T-63, NFR-01-2).
 *
 * <p>A request counts as HTTPS only when {@link HttpServletRequest#isSecure()} is true. Behind the
 * reverse proxy that is set by Tomcat's RemoteIpValve, and only for requests from
 * {@code TRUSTED_PROXY_REGEX} addresses; a client-supplied {@code X-Forwarded-Proto} is ignored.
 * 308 keeps the method and body, so a POST stays a POST.
 *
 * <p>M-1: the redirect host is the configured {@code PUBLIC_HOST}, never the request's
 * {@code Host} header, so the (cacheable) redirect cannot point anywhere else. Without a valid
 * public host the request is refused with 400 and no {@code Location}.
 *
 * <p>{@value #HEALTH_PATH} is exempt so the proxy can probe it over plain HTTP; it shows no details.
 * Not a Spring bean: it is created and added to the security chain only when HTTPS is enforced.
 */
public class HttpsEnforcementFilter extends OncePerRequestFilter {

    static final String HEALTH_PATH = "/actuator/health";

    private final String publicHost;

    /**
     * @param publicHost {@code host} or {@code host:port} clients use; blank or invalid means
     *                   plain HTTP is refused instead of redirected
     */
    public HttpsEnforcementFilter(String publicHost) {
        this.publicHost = SecuritySettingRules.isValidPublicHost(publicHost)
                ? publicHost.trim().toLowerCase(Locale.ROOT)
                : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.isSecure() || HEALTH_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (publicHost == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/problem+json");
            response.getOutputStream().write(
                    "{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"HTTPS required\"}"
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        var target = new StringBuilder("https://").append(publicHost).append(request.getRequestURI());
        if (request.getQueryString() != null) {
            target.append('?').append(request.getQueryString());
        }
        response.setStatus(HttpServletResponse.SC_PERMANENT_REDIRECT);
        response.setHeader("Location", target.toString());
    }
}
