package com.finsight.auth.repository;

import com.finsight.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link RefreshToken} entities.
 *
 * <p>The {@link #findByTokenHashForUpdate} method acquires a row-level
 * {@code SELECT … FOR UPDATE} lock, which is required for safe concurrent
 * rotation (AC-TOK-7). It must only be called inside an active transaction.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Looks up a token by its SHA-256 hash with a pessimistic write lock.
     *
     * <p>Must be called inside a {@code @Transactional} method.
     * The lock prevents two concurrent refresh requests from both succeeding
     * with the same token (AC-TOK-7).
     *
     * @param tokenHash SHA-256 hex digest of the raw token
     * @return the matching row, locked for update, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Looks up a token by hash without a lock — used for quick read-only checks.
     *
     * @param tokenHash SHA-256 hex digest
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Returns all active (non-revoked) tokens for a user.
     *
     * <p>Used during re-use detection to bulk-revoke all sessions.
     *
     * @param userId the user whose active tokens should be returned
     */
    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);

    /**
     * Bulk-revokes all active tokens for a user, setting the given revocation reason.
     *
     * <p>Called from {@link com.finsight.auth.service.TokenService} when re-use
     * of a {@code ROTATED}-revoked token is detected. Must run inside a transaction.
     *
     * @param userId           the user whose active tokens should be revoked
     * @param revocationReason the reason to record (always REUSE_DETECTED in practice)
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
            SET rt.revoked = true, rt.revocationReason = :reason
            WHERE rt.userId = :userId AND rt.revoked = false
            """)
    void revokeAllActiveForUser(
            @Param("userId") Long userId,
            @Param("reason") com.finsight.auth.domain.RevocationReason revocationReason);
}
