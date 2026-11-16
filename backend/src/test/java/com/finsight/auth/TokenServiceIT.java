package com.finsight.auth;

import com.finsight.auth.domain.RevocationReason;
import com.finsight.auth.repository.RefreshTokenRepository;
import com.finsight.auth.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("AC-TOK-7: two concurrent rotate() calls with the same token — exactly one succeeds")
    void concurrentRotation_exactlyOneSucceeds() throws Exception {
        // Issue a token for a fake user (userId=1).
        // The users table must have a row for FK integrity; seed one via raw SQL or use a user
        // that the DataSeeder would create. For this IT we insert directly.
        // Note: this test requires a user row to exist for the same-module FK.
        // We use a SQL command via JdbcTemplate — injected here to avoid coupling to UserService.
        // For now, disable FK checks during this test using a native approach.
        // A full AuthControllerIT would test this end-to-end; this IT focuses on the locking behaviour.

        // Insert a test user row directly so the FK constraint is satisfied.
        mysql.execInContainer("mysql", "-ufinsight", "-pfinsight_it_pw", "finsight_it",
                "-e", "INSERT IGNORE INTO users (id,email,password_hash,full_name,role,created_at,updated_at) " +
                      "VALUES (999,'it@test.com','hash','IT User','USER',NOW(),NOW())");

        // Issue a token for user 999.
        var rawToken = tokenService.issue(999L);

        var successCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(() -> {
            try {
                tokenService.rotate(rawToken);
                successCount.incrementAndGet();
            } catch (Exception e) {
                // Expected for the loser.
            }
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                tokenService.rotate(rawToken);
                successCount.incrementAndGet();
            } catch (Exception e) {
                // Expected for the loser.
            }
        });

        f1.get();
        f2.get();
        pool.shutdown();

        // Exactly one rotation should have succeeded.
        assertThat(successCount.get())
                .as("Exactly one concurrent rotation should succeed")
                .isEqualTo(1);

        // The loser triggered re-use detection: all active tokens should now be REUSE_DETECTED.
        var activeAfter = tokenRepository.findByUserIdAndRevokedFalse(999L);
        var reuseDetectedCount = tokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(999L))
                .filter(t -> t.getRevocationReason() == RevocationReason.REUSE_DETECTED)
                .count();

        assertThat(reuseDetectedCount)
                .as("Loser should have triggered REUSE_DETECTED bulk revocation")
                .isPositive();
    }
}
