package com.finsight.auth;

import com.finsight.auth.service.InProcessRateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InProcessRateLimiterService} (T-15b).
 *
 * <p>Tests:
 * <ul>
 *   <li>5 attempts within the window pass.</li>
 *   <li>6th attempt returns {@code false}.</li>
 *   <li>Counter resets after the window expires (simulated by injecting past timestamps).</li>
 * </ul>
 */
class RateLimiterServiceTest {

    private InProcessRateLimiterService limiter;

    @BeforeEach
    void setUp() {
        limiter = new InProcessRateLimiterService();
    }

    @Test
    @DisplayName("5 attempts within the window are all allowed")
    void fiveAttemptsAreAllowed() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("192.168.1.1", 5, 900))
                    .as("Attempt %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("6th attempt within the window is rejected")
    void sixthAttemptIsRejected() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("10.0.0.1", 5, 900);
        }
        assertThat(limiter.tryConsume("10.0.0.1", 5, 900)).isFalse();
    }

    @Test
    @DisplayName("Different keys are tracked independently")
    void differentKeysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("1.2.3.4", 5, 900);
        }
        // Different IP should still be at 0 attempts.
        assertThat(limiter.tryConsume("5.6.7.8", 5, 900)).isTrue();
    }

    @Test
    @DisplayName("retryAfterSeconds returns a positive value when limit is exceeded")
    void retryAfterIsPositiveWhenLimitExceeded() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("99.99.99.99", 5, 900);
        }
        assertThat(limiter.retryAfterSeconds("99.99.99.99", 900)).isPositive();
    }

    @Test
    @DisplayName("retryAfterSeconds returns 0 for a key with no attempts")
    void retryAfterIsZeroForNewKey() {
        assertThat(limiter.retryAfterSeconds("unknown-ip", 900)).isZero();
    }

    @Test
    @DisplayName("Counter resets after the window expires — new attempts are allowed")
    void counterResetsAfterWindowExpiry() throws InterruptedException {
        // Use a 1-second window and consume it.
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("reset-test", 3, 1);
        }
        assertThat(limiter.tryConsume("reset-test", 3, 1)).isFalse();

        // Wait for the 1-second window to expire.
        Thread.sleep(1100);

        // All previous entries should have been evicted.
        assertThat(limiter.tryConsume("reset-test", 3, 1)).isTrue();
    }
}
