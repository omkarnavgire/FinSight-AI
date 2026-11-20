package com.finsight.auth;

import com.finsight.auth.domain.RevocationReason;
import com.finsight.auth.repository.RefreshTokenRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.shared.exception.InvalidTokenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for concurrent token rotation (T-11b, AC-TOK-7).
 *
 * <p>Two concurrent {@code rotate()} calls with the same token must result in exactly one
 * successful rotation; the other must trigger re-use detection (setting all active tokens
 * for that user to {@code REUSE_DETECTED}).
 */
@SpringBootTest
@Testcontainers
class TokenServiceIT {

    /** User IDs for the concurrency test; separate from the 1001–1004 range used below. */
    private static final AtomicLong CONCURRENT_USERS = new AtomicLong(2000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret",
                () -> "it-test-secret-must-be-at-least-32-chars-ok!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RefreshTokenRepository tokenRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("AC-TOK-7: two concurrent rotate() calls with the same token: one succeeds, the other is a 401 that triggers re-use detection")
    void concurrentRotation_exactlyOneSucceeds() throws Exception {
        // Fresh user per invocation, so repeated runs never see data from an earlier run.
        long userId = CONCURRENT_USERS.incrementAndGet();
        insertUser(userId);
        var rawToken = tokenService.issue(userId);

        // Both threads wait at the gate, then call rotate() at the same moment.
        var gate = new CountDownLatch(1);
        Callable<String> attempt = () -> {
            gate.await();
            try {
                tokenService.rotate(rawToken);
                return "ROTATED";
            } catch (InvalidTokenException expectedForLoser) {
                return "INVALID_TOKEN";
            }
            // Any other exception (deadlock, lock timeout, other database error) is NOT caught:
            // Future.get() rethrows it below and the test fails with the real cause.
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(attempt);
            Future<String> f2 = pool.submit(attempt);
            gate.countDown();

            assertThat(List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS)))
                    .as("Exactly one rotation succeeds and the other is rejected with InvalidTokenException")
                    .containsExactlyInAnyOrder("ROTATED", "INVALID_TOKEN");
        } finally {
            pool.shutdownNow();
        }

        // The loser saw ROTATED and triggered re-use detection: no active token is left,
        // including the one the winner just received.
        assertThat(tokenRepository.findByUserIdAndRevokedFalse(userId))
                .as("Re-use detection must revoke every active token of the user")
                .isEmpty();
        var reuseDetectedCount = tokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .filter(t -> t.getRevocationReason() == RevocationReason.REUSE_DETECTED)
                .count();
        assertThat(reuseDetectedCount)
                .as("Loser should have triggered REUSE_DETECTED bulk revocation")
                .isPositive();
        assertThat(reasonOf(rawToken)).isEqualTo(RevocationReason.ROTATED);
    }

    // ---- Persisted-state tests (AC-TOK-2, AC-TOK-2b, AC-TOK-3) ----
    // Each test uses its own user IDs: the MySQL container is shared by the whole class.

    @Test
    @DisplayName("AC-TOK-2: ROTATED re-use commits REUSE_DETECTED for every active token; other users unaffected")
    void rotatedReuse_persistsReuseDetected_andOtherUsersUnaffected() {
        insertUser(1001L);
        insertUser(1002L);

        var stolenToken = tokenService.issue(1001L);
        var secondSession = tokenService.issue(1001L);
        var otherUserToken = tokenService.issue(1002L);

        // Legitimate rotation: stolenToken becomes ROTATED, a new token is issued.
        var rotated = tokenService.rotate(stolenToken);

        // Attacker replays the ROTATED token.
        assertThatThrownBy(() -> tokenService.rotate(stolenToken))
                .isInstanceOf(InvalidTokenException.class);

        // Committed DB state: user 1001 has no active tokens left.
        assertThat(tokenRepository.findByUserIdAndRevokedFalse(1001L))
                .as("All of user 1001's active tokens must be revoked and committed")
                .isEmpty();
        assertThat(reasonOf(secondSession)).isEqualTo(RevocationReason.REUSE_DETECTED);
        assertThat(reasonOf(rotated.newRawToken())).isEqualTo(RevocationReason.REUSE_DETECTED);
        // The replayed token keeps its original ROTATED reason.
        assertThat(reasonOf(stolenToken)).isEqualTo(RevocationReason.ROTATED);

        // A different user's session is not touched.
        var other = tokenRepository.findByTokenHash(TokenService.sha256Hex(otherUserToken)).orElseThrow();
        assertThat(other.isRevoked()).isFalse();
        assertThat(other.getRevocationReason()).isNull();
    }

    @Test
    @DisplayName("AC-TOK-2b: re-presenting a REUSE_DETECTED token returns 401 and triggers no further cascade")
    void reuseDetectedToken_representation_noFurtherCascade() {
        insertUser(1003L);

        var stolenToken = tokenService.issue(1003L);
        var victimSession = tokenService.issue(1003L);
        tokenService.rotate(stolenToken);
        assertThatThrownBy(() -> tokenService.rotate(stolenToken))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(reasonOf(victimSession)).isEqualTo(RevocationReason.REUSE_DETECTED);

        // User logs in again after the incident: a fresh active session.
        var freshSession = tokenService.issue(1003L);

        // The REUSE_DETECTED token is presented again.
        assertThatThrownBy(() -> tokenService.rotate(victimSession))
                .isInstanceOf(InvalidTokenException.class);

        // No new cascade: the fresh session is still active.
        var fresh = tokenRepository.findByTokenHash(TokenService.sha256Hex(freshSession)).orElseThrow();
        assertThat(fresh.isRevoked())
                .as("REUSE_DETECTED re-presentation must not revoke the fresh session")
                .isFalse();
        assertThat(reasonOf(victimSession)).isEqualTo(RevocationReason.REUSE_DETECTED);
    }

    @Test
    @DisplayName("AC-TOK-3: re-presenting a LOGOUT token returns 401; the user's other session stays active")
    void logoutToken_representation_otherSessionStaysActive() {
        insertUser(1004L);

        var loggedOut = tokenService.issue(1004L);
        var otherSession = tokenService.issue(1004L);
        tokenService.revoke(loggedOut, RevocationReason.LOGOUT);

        assertThatThrownBy(() -> tokenService.rotate(loggedOut))
                .isInstanceOf(InvalidTokenException.class);

        var other = tokenRepository.findByTokenHash(TokenService.sha256Hex(otherSession)).orElseThrow();
        assertThat(other.isRevoked()).isFalse();
        assertThat(reasonOf(loggedOut)).isEqualTo(RevocationReason.LOGOUT);
    }

    // ---- S-1 regression: logout with a stolen ROTATED token must not silence re-use detection ----

    @Test
    @DisplayName("S-1: attacker logs out with the rotated token; the victim's replay still triggers REUSE_DETECTED")
    void logoutWithRotatedToken_doesNotSuppressReuseDetection() {
        insertUser(1005L);
        var stolen = tokenService.issue(1005L);

        // Attacker rotates first and gets a live session; the stolen token is now ROTATED.
        var attackerSession = tokenService.rotate(stolen).newRawToken();
        // Attacker "logs out" with the stolen token (same user identity) to try to downgrade it.
        tokenService.revokeForLogout(stolen, 1005L);
        assertThat(reasonOf(stolen)).isEqualTo(RevocationReason.ROTATED);

        // The victim presents the stolen token: re-use detection still fires and is committed.
        assertThatThrownBy(() -> tokenService.rotate(stolen)).isInstanceOf(InvalidTokenException.class);
        assertThat(reasonOf(attackerSession)).isEqualTo(RevocationReason.REUSE_DETECTED);
        assertThat(tokenRepository.findByUserIdAndRevokedFalse(1005L)).isEmpty();
    }

    @Test
    @DisplayName("S-1: logout with another user's token is rejected and leaves that token active")
    void logoutWithOtherUsersToken_rejected() {
        insertUser(1006L);
        insertUser(1007L);
        var victimToken = tokenService.issue(1006L);

        assertThatThrownBy(() -> tokenService.revokeForLogout(victimToken, 1007L))
                .isInstanceOf(InvalidTokenException.class);

        var victim = tokenRepository.findByTokenHash(TokenService.sha256Hex(victimToken)).orElseThrow();
        assertThat(victim.isRevoked()).isFalse();
        assertThat(victim.getRevocationReason()).isNull();
    }

    // ---- Helpers ----

    private void insertUser(long id) {
        jdbc.update("""
                INSERT IGNORE INTO users (id, email, password_hash, full_name, role, created_at, updated_at)
                VALUES (?, ?, 'hash', 'IT User', 'USER', NOW(), NOW())
                """, id, "it-" + id + "@test.com");
    }

    private RevocationReason reasonOf(String rawToken) {
        return tokenRepository.findByTokenHash(TokenService.sha256Hex(rawToken))
                .orElseThrow()
                .getRevocationReason();
    }
}
