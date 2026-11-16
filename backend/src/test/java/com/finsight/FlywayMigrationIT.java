package com.finsight;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies all Flyway migrations (V1–V7) apply cleanly
 * against a real MySQL 8 instance provided by Testcontainers.
 *
 * <p>This is the authoritative test for migration correctness. Each module's
 * migration file is exercised in version order. The test passes only when every
 * script executes without error and Flyway reports zero pending migrations.
 */
@SpringBootTest
@Testcontainers
class FlywayMigrationIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_test")
            .withUsername("finsight")
            .withPassword("finsight_test_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret",
                () -> "integration-test-secret-must-be-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("All Flyway migrations apply without errors and no migrations are pending")
    void allMigrationsApplyCleanly() {
        var info = flyway.info();
        long pendingCount = java.util.Arrays.stream(info.all())
                .filter(m -> m.getState().name().startsWith("PENDING"))
                .count();
        assertThat(pendingCount)
                .as("No migrations should be pending after context startup")
                .isZero();
    }

    @Test
    @DisplayName("Migration count matches expected V1 through V7")
    void migrationCountIsCorrect() {
        var info = flyway.info();
        long appliedCount = java.util.Arrays.stream(info.all())
                .filter(m -> m.getState().isApplied())
                .count();
        assertThat(appliedCount)
                .as("Expected exactly 7 applied migrations (V1–V7)")
                .isEqualTo(7);
    }
}
