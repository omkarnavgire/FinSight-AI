package com.finsight.auth.service;

import com.finsight.shared.exception.InvalidTokenException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates HS256-signed JWT access tokens.
 *
 * <p>The signing secret is read from the {@code finsight.security.jwt.secret}
 * environment-backed property — it is never hardcoded.
 *
 * <p>Access token claims:
 * <ul>
 *   <li>{@code sub} — user ID (as string)</li>
 *   <li>{@code email} — user email</li>
 *   <li>{@code role} — user role (e.g. {@code USER})</li>
 *   <li>{@code iat} / {@code exp} — issued-at and expiry timestamps</li>
 * </ul>
 *
 * <p>Token values are NEVER logged.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessExpiryMs;

    public JwtService(
            @Value("${finsight.security.jwt.secret}") String secret,
            @Value("${finsight.security.jwt.access-expiry-ms:900000}") long accessExpiryMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMs = accessExpiryMs;
    }

    /**
     * Issues a signed HS256 access token for the given user.
     *
     * @param userId user's surrogate PK
     * @param email  user's email address
     * @param role   user's role string (e.g. "USER")
     * @return signed compact JWT string
     */
    public String issueAccessToken(Long userId, String email, String role) {
        var now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessExpiryMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates a JWT and returns its claims.
     *
     * @param token the compact JWT string
     * @return validated claims
     * @throws InvalidTokenException if the token is malformed, expired, or has an invalid signature
     */
    public Claims validateAndParse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("JWT validation failed");
        }
    }

    /**
     * Extracts the user ID from a validated token.
     *
     * @param token the compact JWT string
     * @return the user ID embedded in the {@code sub} claim
     * @throws InvalidTokenException if the token is invalid
     */
    public Long extractUserId(String token) {
        return Long.parseLong(validateAndParse(token).getSubject());
    }

    /** @return the access token lifetime in seconds (for the {@code expires_in} response field). */
    public long getAccessExpirySeconds() {
        return accessExpiryMs / 1000;
    }
}
