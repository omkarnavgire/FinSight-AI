package com.finsight.auth.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * JPA entity for a persisted refresh token.
 *
 * <p>The raw token value is NEVER stored. Only the SHA-256 hex digest is persisted
 * in {@code token_hash}. (AC-TOK-6)
 *
 * <p>Rotation and revocation are handled by {@link com.finsight.auth.service.TokenService}.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Same-module FK → users.id; enforced by DB constraint. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the raw 256-bit token. The raw value never leaves memory. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 20)
    private RevocationReason revocationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RefreshToken() {}

    /** Factory called only from {@link com.finsight.auth.service.TokenService}. */
    public static RefreshToken create(Long userId, String tokenHash, LocalDateTime expiresAt) {
        var rt = new RefreshToken();
        rt.userId = userId;
        rt.tokenHash = tokenHash;
        rt.expiresAt = expiresAt;
        rt.revoked = false;
        rt.revocationReason = null;
        rt.createdAt = LocalDateTime.now();
        return rt;
    }

    /** Marks this token as revoked with the given reason. */
    public void revoke(RevocationReason reason) {
        this.revoked = true;
        this.revocationReason = reason;
    }

    /** @return true if this token is past its expiry time. */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    // ---- Getters ----

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    /** Returns the stored hash. Never returns the raw token. */
    public String getTokenHash() { return tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }

    public boolean isRevoked() { return revoked; }

    public RevocationReason getRevocationReason() { return revocationReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
