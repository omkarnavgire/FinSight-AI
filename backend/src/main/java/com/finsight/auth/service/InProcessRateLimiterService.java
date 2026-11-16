package com.finsight.auth.service;

import com.finsight.shared.security.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process sliding-window rate limiter.
 *
 * <p><strong>Single-instance limitation (AC-RL-1):</strong> State lives in JVM heap memory.
 * In a multi-instance deployment each JVM maintains an independent counter, so a client
 * can bypass the limit by routing requests across instances. This implementation is
 * intentional for the MVP single-instance deployment only.
 *
 * <p><strong>Production scaling path (AC-RL-1):</strong> Replace this bean with a
 * Redis-backed implementation using the same {@link RateLimiter} interface.
 * A sorted-set approach ({@code ZADD} + {@code ZREMRANGEBYSCORE} + {@code ZCARD})
 * provides an exact sliding window without shared mutable state issues.
 * No call sites need to change — only the Spring bean wiring.
 *
 * <p>State also resets on application restart: a client blocked just before restart
 * can immediately retry. This is an accepted limitation for the MVP.
 */
@Service
public class InProcessRateLimiterService implements RateLimiter {

    /**
     * Per-key deque of attempt timestamps (epoch milliseconds).
     * Entries older than the window are evicted on each {@link #tryConsume} call.
     */
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>Evicts stale entries, then checks whether adding this attempt would exceed
     * {@code maxAttempts}. Thread-safe via per-key synchronisation on the deque.
     */
    @Override
    public boolean tryConsume(String key, int maxAttempts, long windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        var deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (deque) {
            // Evict attempts that have fallen outside the window.
            while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
                deque.pollFirst();
            }

            if (deque.size() >= maxAttempts) {
                return false;
            }

            deque.addLast(now);
            return true;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the time until the oldest in-window attempt drops off,
     * giving the caller the minimum wait before another attempt is accepted.
     */
    @Override
    public long retryAfterSeconds(String key, long windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        var deque = attempts.get(key);
        if (deque == null) return 0L;

        synchronized (deque) {
            // The oldest in-window entry is the one that will expire soonest.
            Long oldest = deque.peekFirst();
            if (oldest == null || oldest <= cutoff) return 0L;
            return Math.max(1L, (oldest + windowMs - now) / 1000L);
        }
    }
}
