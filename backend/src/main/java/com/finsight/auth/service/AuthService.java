package com.finsight.auth.service;

import com.finsight.auth.domain.User;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.auth.dto.TokenResponse;
import com.finsight.auth.repository.UserRepository;
import com.finsight.shared.exception.AuthenticationFailedException;
import com.finsight.shared.exception.RateLimitExceededException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.shared.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles user registration, login, token refresh, and logout.
 *
 * <p>All interactions with token state go through {@link TokenService}.
 * This service never reads or stores raw token values beyond the scope of a single method call.
 *
 * <p>Sensitive fields ({@code password}, token values) are never logged.
 */
@Service
public class AuthService {

    static final int LOGIN_MAX_ATTEMPTS = 5;
    static final long LOGIN_WINDOW_SECONDS = 15 * 60L; // 15 minutes

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenService tokenService,
            RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Registers a new user.
     *
     * @param request validated register request
     * @return access token response (refresh token delivered via cookie — see controller)
     * @throws ResponseStatusException 409 if the email is already registered
     */
    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists");
        }
        var hash = passwordEncoder.encode(request.password());
        var user = User.create(request.email(), hash, request.fullName());
        userRepository.save(user);

        var accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(),
                user.getRole().name());
        var rawRefreshToken = tokenService.issue(user.getId());
        return new AuthResult(accessToken, rawRefreshToken, user.getId());
    }

    /**
     * Authenticates a user and issues tokens.
     *
     * <p>Rate-limited: {@value #LOGIN_MAX_ATTEMPTS} attempts per {@value #LOGIN_WINDOW_SECONDS}
     * seconds per IP address (enforced in {@link com.finsight.auth.controller.AuthController}).
     *
     * @param request  validated login request
     * @param clientIp the client's IP address (extracted by the controller)
     * @return access token response (refresh token delivered via cookie — see controller)
     * @throws RateLimitExceededException if the rate limit is exceeded for this IP
     * @throws AuthenticationFailedException if credentials are wrong (intentionally vague — no
     *         distinction between unknown email and wrong password to prevent user enumeration)
     */
    @Transactional
    public AuthResult login(LoginRequest request, String clientIp) {
        if (!rateLimiter.tryConsume(clientIp, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_SECONDS)) {
            throw new RateLimitExceededException(
                    rateLimiter.retryAfterSeconds(clientIp, LOGIN_WINDOW_SECONDS));
        }

        var user = userRepository.findByEmail(request.email())
                .orElseThrow(AuthenticationFailedException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new AuthenticationFailedException();
        }

        var accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(),
                user.getRole().name());
        var rawRefreshToken = tokenService.issue(user.getId());
        return new AuthResult(accessToken, rawRefreshToken, user.getId());
    }

    /**
     * Rotates the refresh token and issues a new access token.
     *
     * <p>Deliberately NOT {@code @Transactional}. {@link TokenService#rotate} is the transaction
     * boundary. If this method opened an outer transaction, {@code rotate()} would join it, and the
     * {@code InvalidTokenException} propagating out of here would roll back the REUSE_DETECTED
     * bulk revocation that must be persisted (AC-TOK-2). The user lookups below are read-only.
     *
     * @param rawRefreshToken the raw token extracted from the HttpOnly cookie
     * @return new tokens (new refresh token delivered via cookie — see controller)
     */
    public AuthResult refresh(String rawRefreshToken) {
        var result = tokenService.rotate(rawRefreshToken);
        var email = resolveEmail(result.userId());
        var role = resolveRole(result.userId());
        var accessToken = jwtService.issueAccessToken(result.userId(), email, role);
        return new AuthResult(accessToken, result.newRawToken(), result.userId());
    }

    /**
     * Revokes the current refresh token and clears the cookie.
     *
     * @param rawRefreshToken the raw token from the cookie; {@code null} means already-cleared
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            // S-1: only the caller's own, still-active token is revoked; see TokenService#revokeForLogout.
            tokenService.revokeForLogout(rawRefreshToken, SecurityUtils.currentUserId());
        }
    }

    // ---- Private helpers ----

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId)
                .map(User::getEmail)
                .orElseThrow(() -> new AuthenticationFailedException());
    }

    private String resolveRole(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRole().name())
                .orElseThrow(() -> new AuthenticationFailedException());
    }

    /**
     * Value object returned from authentication operations.
     * The raw refresh token is handled only by the controller cookie builder.
     */
    public record AuthResult(String accessToken, String rawRefreshToken, Long userId) {}
}
