package com.finsight.shared.exception;

/**
 * Thrown when a caller exceeds a rate limit enforced through
 * {@link com.finsight.shared.security.RateLimiter}.
 *
 * <p>The limit key depends on the endpoint, for example:
 * <ul>
 *   <li>per client IP: login, 5 attempts per 15 minutes;</li>
 *   <li>per authenticated user ({@code cashflow-refresh:<userId>}): cash-flow refresh, 1 per hour.</li>
 * </ul>
 *
 * <p>Maps to HTTP 429 Too Many Requests via {@link com.finsight.shared.advice.GlobalExceptionHandler}.
 * The {@code retryAfterSeconds} field is surfaced in the {@code Retry-After} response header.
 */
public class RateLimitExceededException extends FinSightException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until the rate-limit window resets for this client. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
