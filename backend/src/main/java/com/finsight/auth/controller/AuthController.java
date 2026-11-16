package com.finsight.auth.controller;

import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.auth.dto.TokenResponse;
import com.finsight.auth.service.AuthService;
import com.finsight.auth.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Handles authentication: register, login, refresh, and logout.
 *
 * <p>Cookie policy (applied on login and refresh responses):
 * <ul>
 *   <li>{@code HttpOnly=true} — always</li>
 *   <li>{@code Secure} — {@code true} in prod/non-local; {@code false} in dev (HTTP only)</li>
 *   <li>{@code SameSite=Strict} — always</li>
 *   <li>{@code Path=/api/v1/auth} — always</li>
 *   <li>{@code Max-Age=604800} (7 days) — always</li>
 * </ul>
 *
 * <p>The {@code Secure} attribute is read from {@code finsight.security.cookie.secure}
 * (default {@code true}; set to {@code false} in {@code application-dev.yml} only).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    static final String REFRESH_COOKIE_NAME = "refreshToken";
    static final long REFRESH_COOKIE_MAX_AGE = 604800L; // 7 days in seconds

    private final AuthService authService;
    private final JwtService jwtService;
    private final boolean cookieSecure;

    /**
     * Number of trusted reverse-proxy hops in front of this application.
     *
     * <p>Default is {@code 0}: trust only {@code remoteAddr} (safe for local dev and
     * direct deployments). Set to {@code 1} when behind a single trusted load-balancer
     * that sets {@code X-Forwarded-For}. A value > 0 reads that many hops from the
     * rightmost end of the header, skipping any client-supplied prefix.
     *
     * <p>Never trust arbitrary client-supplied values: a client can inject any IP into
     * the leftmost positions of {@code X-Forwarded-For}. Only the rightmost N entries
     * (added by the N trusted proxies) are reliable.
     *
     * <p>See: <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-For">
     * MDN: X-Forwarded-For</a>
     */
    private final int trustedProxyCount;

    public AuthController(
            AuthService authService,
            JwtService jwtService,
            @Value("${finsight.security.cookie.secure:true}") boolean cookieSecure,
            @Value("${finsight.security.trusted-proxy-count:0}") int trustedProxyCount) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.cookieSecure = cookieSecure;
        this.trustedProxyCount = trustedProxyCount;
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException(
                    "finsight.security.trusted-proxy-count must be >= 0, got: " + trustedProxyCount);
        }
    }

    /**
     * Registers a new user.
     *
     * @param request validated registration payload
     * @return 201 Created with access token body and refresh token cookie
     */
    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        var result = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.rawRefreshToken()).toString())
                .body(buildTokenResponse(result));
    }

    /**
     * Authenticates a user.
     *
     * <p>Rate-limited to 5 attempts per 15 minutes per IP (enforced in {@link AuthService}).
     *
     * @param request   validated login payload
     * @param rawRequest the raw HTTP request used to extract the client IP
     * @return 200 OK with access token body and refresh token cookie
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest rawRequest) {
        var clientIp = extractClientIp(rawRequest);
        var result = authService.login(request, clientIp);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.rawRefreshToken()).toString())
                .body(buildTokenResponse(result));
    }

    /**
     * Rotates the refresh token and issues a new access token.
     *
     * <p>The raw refresh token is read from the {@code refreshToken} HttpOnly cookie.
     * Requires a valid {@code Origin} or {@code Referer} header (enforced by
     * {@link com.finsight.auth.filter.OriginRefererFilter}).
     *
     * @return 200 OK with new access token body and new refresh token cookie
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var result = authService.refresh(rawRefreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.rawRefreshToken()).toString())
                .body(buildTokenResponse(result));
    }

    /**
     * Revokes the refresh token and clears the cookie.
     *
     * @param rawRefreshToken the current refresh token from the cookie
     * @return 204 No Content with a cleared cookie
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String rawRefreshToken) {
        authService.logout(rawRefreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, buildClearCookie().toString())
                .build();
    }

    // ---- Private helpers ----

    private ResponseCookie buildRefreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(REFRESH_COOKIE_MAX_AGE)
                .build();
    }

    /** Builds a zero-age cookie to instruct the browser to delete the refresh cookie. */
    private ResponseCookie buildClearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }

    private TokenResponse buildTokenResponse(AuthService.AuthResult result) {
        return TokenResponse.bearer(result.accessToken(), jwtService.getAccessExpirySeconds());
    }

    /**
     * Extracts the real client IP using a trusted-proxy-count-aware algorithm.
     *
     * <p>When {@code trustedProxyCount == 0} (the default), {@code remoteAddr} is returned
     * directly — {@code X-Forwarded-For} is completely ignored and cannot be forged.
     *
     * <p>When {@code trustedProxyCount > 0}, the header is split and the entry at position
     * {@code (total - trustedProxyCount)} from the left is returned. Entries to the left of
     * that position are client-supplied and therefore untrusted; only the rightmost N entries
     * (appended by the N known proxy hops) are reliable.
     *
     * <p>If the header has fewer entries than {@code trustedProxyCount}, the leftmost entry
     * is used as a best-effort fallback, and a WARN is logged.
     */
    String extractClientIp(HttpServletRequest request) {
        if (trustedProxyCount == 0) {
            return request.getRemoteAddr();
        }

        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }

        var parts = forwarded.split(",");
        // Index of the first trusted entry from the right.
        int trustedIndex = parts.length - trustedProxyCount;
        if (trustedIndex < 0) {
            log.warn("X-Forwarded-For has {} entries but trustedProxyCount={}; " +
                     "using leftmost entry as fallback. Check trusted-proxy-count config.",
                     parts.length, trustedProxyCount);
            trustedIndex = 0;
        }
        return parts[trustedIndex].trim();
    }
}
