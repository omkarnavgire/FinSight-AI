package com.finsight.auth.domain;

/**
 * Reason a refresh token was revoked.
 *
 * <p>Behaviour on subsequent presentation:
 * <ul>
 *   <li>{@code ROTATED} — triggers re-use detection; all active tokens for the user are
 *       revoked with {@code REUSE_DETECTED}; returns 401.</li>
 *   <li>{@code LOGOUT} — returns 401; no further action; other sessions unaffected.</li>
 *   <li>{@code REUSE_DETECTED} — returns 401; re-use detection is NOT triggered again
 *       (avoids cascading bulk-revocation cycles).</li>
 * </ul>
 */
public enum RevocationReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED
}
