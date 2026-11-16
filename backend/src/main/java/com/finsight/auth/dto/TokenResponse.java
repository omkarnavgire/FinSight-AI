package com.finsight.auth.dto;

/**
 * Response body for {@code POST /api/v1/auth/login} and {@code POST /api/v1/auth/refresh}.
 *
 * <p>Contains only the short-lived access token. The refresh token is delivered
 * exclusively via an HttpOnly cookie — it is never included in this record.
 *
 * @param accessToken  signed HS256 JWT; 15-minute expiry
 * @param tokenType    always {@code "Bearer"}
 * @param expiresIn    access token lifetime in seconds (900)
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    /** Convenience factory for a standard Bearer response. */
    public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
