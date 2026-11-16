package com.finsight.shared.security;

/**
 * Interface for per-key sliding-window rate limiting.
 *
 * <p>The MVP implementation is {@link com.finsight.auth.service.InProcessRateLimiterService},
 * which is single-instance only. When the application scales beyond one JVM instance,
 * this interface should be reimplemented with a Redis-backed sliding-window
 * (e.g. {@code INCR} + {@code EXPIRE} or a sorted-set approach) without changing any call sites.
 *
 * <p>See AC-RL-2 (interface kept stable; implementation swappable).
 */
public interface RateLimiter {

    /**
     * Records one attempt for the given key and checks whether the limit is exceeded.
     *
     * @param key            the rate-limit key (typically a client IP address)
     * @param maxAttempts    maximum number of allowed attempts within the window
     * @param windowSeconds  the sliding window size in seconds
     * @return {@code true} if the attempt is within the allowed limit;
     *         {@code false} if the limit has been exceeded
     */
    boolean tryConsume(String key, int maxAttempts, long windowSeconds);

    /**
     * Returns the number of seconds until the oldest recorded attempt for this key
     * falls outside the window, effectively resetting the counter.
     *
     * <p>Use this value for the {@code Retry-After} response header.
     *
     * @param key            the rate-limit key
     * @param windowSeconds  the sliding window size in seconds
     * @return seconds remaining until the window resets; 0 if no attempts recorded
     */
    long retryAfterSeconds(String key, long windowSeconds);
}
