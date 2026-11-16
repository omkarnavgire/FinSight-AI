package com.finsight.shared.exception;

/**
 * Thrown when a client exceeds the per-IP rate limit on a protected endpoint.
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
