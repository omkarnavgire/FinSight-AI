package com.finsight.auth.service;

import com.finsight.auth.domain.RefreshToken;
import com.finsight.auth.domain.RevocationReason;
import com.finsight.auth.repository.RefreshTokenRepository;
import com.finsight.shared.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Manages refresh token lifecycle: generation, rotation, revocation, and re-use detection.
 *
 * <h2>Token storage (AC-TOK-6)</h2>
 * Raw tokens are 256-bit values from {@link SecureRandom}, encoded as Base64.
 * Only the SHA-256 hex digest is persisted — the raw value never touches the database.
 *
 * <h2>Rotation (AC-TOK-1 / AC-TOK-7)</h2>
 * {@link #rotate} acquires a pessimistic write lock ({@code SELECT … FOR UPDATE}) on the
 * token row before reading its state. Two concurrent requests with the same token compete
 * for the lock; only one proceeds to rotation, the other sees {@code revoked=ROTATED}
 * and triggers re-use detection.
 *
 * <h2>Revocation reasons</h2>
 * <ul>
 *   <li>{@code ROTATED} — set by successful rotation. Re-use triggers bulk revocation
 *       of all active tokens → {@code REUSE_DETECTED}; returns 401.</li>
 *   <li>{@code LOGOUT} — set by explicit logout. Re-use returns 401 only; no cascade.</li>
 *   <li>{@code REUSE_DETECTED} — set by bulk revocation. Re-use returns 401 only;
 *       no further cascade (AC-TOK-2b).</li>
 * </ul>
 */
@Service
public class TokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokenRepository;
    private final long refreshExpiryMs;

    public TokenService(
            RefreshTokenRepository tokenRepository,
            @Value("${finsight.security.jwt.refresh-expiry-ms:604800000}") long refreshExpiryMs) {
        this.tokenRepository = tokenRepository;
        this.refreshExpiryMs = refreshExpiryMs;
    }

    /**
     * Generates a new refresh token for the given user and persists its hash.
     *
     * @param userId the user to issue a token for
     * @return the raw (unhashed) token string — must be delivered to the client
     *         and never stored
     */
    @Transactional
    public String issue(Long userId) {
        var rawToken = generateRawToken();
        var hash = sha256Hex(rawToken);
        var expiresAt = LocalDateTime.now().plusSeconds(refreshExpiryMs / 1000);
        tokenRepository.save(RefreshToken.create(userId, hash, expiresAt));
        return rawToken;
    }

    /**
     * Rotates a refresh token: validates the incoming raw token, marks it {@code ROTATED},
     * and issues a new one — all in a single transaction with a row-level lock (AC-TOK-7).
     *
     * @param rawToken the raw token from the client's cookie
     * @return {@link RotationResult} containing the new raw token and the owner's userId
     * @throws InvalidTokenException if the token is not found, expired, or already revoked
     *         with {@code LOGOUT} or {@code REUSE_DETECTED}
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public RotationResult rotate(String rawToken) {
        // Transaction semantics (AC-TOK-2 / AC-AUTH-2):
        // A rejected token raises InvalidTokenException (-> 401). That exception must NOT roll back
        // this transaction, because the ROTATED re-use path has already bulk-revoked the user's
        // active tokens to REUSE_DETECTED, and that revocation must be committed.
        // Every InvalidTokenException path is safe to commit:
        //   - not found / expired      -> no writes were made
        //   - LOGOUT / REUSE_DETECTED  -> no writes were made (no cascade, AC-TOK-2b / AC-TOK-3)
        //   - ROTATED                  -> only the REUSE_DETECTED bulk revocation was written
        // Any other RuntimeException (e.g. a database error) still rolls back as normal.
        // This method must be the outermost transaction boundary: if a caller wraps it in its
        // own @Transactional, the caller's rollback would undo the revocation. See AuthService.refresh().
        var hash = sha256Hex(rawToken);

        // Acquire row-level lock before reading state (AC-TOK-7).
        var stored = tokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        if (stored.isExpired()) {
            // AC-TOK-4: expired tokens return 401 regardless of revocation state.
            // Re-use detection is NOT triggered for expired tokens, even if the reason is ROTATED.
            // The expiry represents natural token death; theft detection requires a valid non-expired
            // token to be presented with a ROTATED reason.
            throw new InvalidTokenException("Refresh token has expired");
        }

        if (stored.isRevoked()) {
            handleRevokedTokenReuse(stored);
            throw new InvalidTokenException("Refresh token has been revoked");
        }

        Long userId = stored.getUserId();

        // Mark old token as rotated.
        stored.revoke(RevocationReason.ROTATED);
        tokenRepository.save(stored);

        // Issue new token within the same transaction.
        String newRawToken = issue(userId);
        return new RotationResult(newRawToken, userId);
    }

    /** Result of a successful token rotation. */
    public record RotationResult(String newRawToken, Long userId) {}

    /**
     * Revokes the token associated with the given raw value, using the specified reason.
     *
     * <p>Used for logout ({@code LOGOUT}) and administrative revocation.
     *
     * @param rawToken the raw token from the client's cookie
     * @param reason   the revocation reason to record
     * @throws InvalidTokenException if the token is not found
     */
    @Transactional
    public void revoke(String rawToken, RevocationReason reason) {
        var hash = sha256Hex(rawToken);
        var stored = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));
        stored.revoke(reason);
        tokenRepository.save(stored);
    }

    /**
     * Looks up a stored refresh token by its raw value hash (read-only, no lock).
     *
     * @param hash SHA-256 hex of the raw token
     * @return the matching entity
     * @throws InvalidTokenException if not found
     */
    public RefreshToken findByHash(String hash) {
        return tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));
    }

    // ---- Private helpers ----

    /**
     * Handles re-use of an already-revoked token.
     *
     * <p>Only {@code ROTATED} re-use triggers bulk revocation (AC-TOK-2).
     * {@code LOGOUT} and {@code REUSE_DETECTED} re-use return 401 only — no cascade (AC-TOK-2b, AC-TOK-3).
     */
    private void handleRevokedTokenReuse(RefreshToken stored) {
        if (stored.getRevocationReason() == RevocationReason.ROTATED) {
            // Possible token theft — revoke all active sessions for this user.
            tokenRepository.revokeAllActiveForUser(stored.getUserId(), RevocationReason.REUSE_DETECTED);
        }
        // LOGOUT and REUSE_DETECTED: return 401 without further action.
    }

    /** Generates a cryptographically random 256-bit token encoded as Base64. */
    private static String generateRawToken() {
        var bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hex digest of a string value.
     *
     * <p>This is the only form of the token ever written to the database (AC-TOK-6).
     * Public to allow use in tests and in {@link AuthService}.
     *
     * @param input the raw token string
     * @return lowercase hex-encoded SHA-256 digest (64 characters)
     */
    public static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
