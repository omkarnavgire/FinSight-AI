package com.finsight.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF second-layer defence for cookie-authenticated auth endpoints.
 *
 * <p>Applies only to {@code POST /api/v1/auth/*}. For all other paths — including
 * GET requests — this filter is a no-op (access tokens travel in {@code Authorization}
 * headers and are inherently CSRF-safe).
 *
 * <h2>Algorithm (see design.md CSRF section)</h2>
 * <ol>
 *   <li>Read the {@code Origin} header; if present, normalise and compare against the allowlist.</li>
 *   <li>If {@code Origin} is absent, derive an effective origin from {@code Referer} using the
 *       same normalisation (extract scheme + host + effective port; ignore path/query/fragment).</li>
 *   <li>If both headers are absent, reject with {@code 403}.</li>
 *   <li>If the normalised value is not in the allowlist, reject with {@code 403}.</li>
 * </ol>
 *
 * <h2>Normalisation steps</h2>
 * <ol>
 *   <li>Parse as URI; reject if unparseable.</li>
 *   <li>Lowercase scheme and host.</li>
 *   <li>Drop the port if it is the scheme default (HTTP→80, HTTPS→443).</li>
 *   <li>Strip path, query, and fragment.</li>
 * </ol>
 *
 * <p>Scheme is never promoted ({@code http} is never converted to {@code https} based on port).
 *
 * <p>Acceptance criteria covered: AC-CSRF-1 through AC-CSRF-9.
 */
@Component
public class OriginRefererFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginRefererFilter.class);

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;

    private final Set<String> allowedOrigins;

    public OriginRefererFilter(
            @Value("${finsight.cors.allowed-origins:http://localhost:5173}") String allowedOriginsConfig) {
        this.allowedOrigins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(raw -> {
                    var normalised = normalise(raw);
                    if (normalised == null) {
                        // B6: Warn at startup — a misconfigured origin is never silently dropped.
                        log.warn("OriginRefererFilter: configured allowed-origin '{}' could not be " +
                                 "normalised and will be ignored. Check finsight.cors.allowed-origins.", raw);
                    }
                    return normalised;
                })
                .filter(o -> o != null)
                .collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Only POST requests to auth endpoints are subject to this filter (AC-CSRF-4).
        if (!HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        var originHeader = request.getHeader("Origin");
        var refererHeader = request.getHeader("Referer");

        // Both absent → reject (AC-CSRF-1).
        if ((originHeader == null || originHeader.isBlank())
                && (refererHeader == null || refererHeader.isBlank())) {
            rejectRequest(response, "Missing Origin and Referer headers");
            return;
        }

        String effectiveOrigin = null;

        if (originHeader != null && !originHeader.isBlank()) {
            effectiveOrigin = normalise(originHeader.trim());
        } else {
            effectiveOrigin = normalise(refererHeader.trim());
        }

        // Malformed or unparseable header → reject (AC-CSRF-5).
        if (effectiveOrigin == null) {
            rejectRequest(response, "Malformed Origin or Referer header");
            return;
        }

        // Not in allowlist → reject (AC-CSRF-2, AC-CSRF-6, AC-CSRF-9).
        if (!allowedOrigins.contains(effectiveOrigin)) {
            rejectRequest(response, "Origin not allowed");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Normalises a raw header value to a canonical {@code scheme://host[:port]} string.
     *
     * <p>Returns {@code null} if the value is malformed or missing required components.
     *
     * <p>Package-private to allow direct testing in {@code OriginRefererFilterTest}.
     *
     * @param raw raw value from {@code Origin} or {@code Referer} header
     * @return normalised origin string, or {@code null} if invalid
     */
    public String normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;

        try {
            var uri = new URI(raw);

            var scheme = uri.getScheme();
            var host = uri.getHost();

            // Reject if scheme or host is missing (AC-CSRF-5).
            if (scheme == null || host == null) return null;

            scheme = scheme.toLowerCase();
            host = host.toLowerCase();

            var port = uri.getPort(); // -1 means no explicit port in the URI

            // Drop port if it is the default for the scheme (AC-CSRF-8, normalisation table).
            // HTTP default is 80; HTTPS default is 443. Scheme is never promoted.
            if (port == -1
                    || (port == HTTP_DEFAULT_PORT && "http".equals(scheme))
                    || (port == HTTPS_DEFAULT_PORT && "https".equals(scheme))) {
                return scheme + "://" + host;
            }

            // Non-default port is preserved (AC-CSRF-9: HTTP:443 kept as :443).
            return scheme + "://" + host + ":" + port;

        } catch (URISyntaxException e) {
            return null; // Malformed URI → caller will reject with 403.
        }
    }

    private static void rejectRequest(HttpServletResponse response, String reason) throws IOException {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, reason);
    }
}
