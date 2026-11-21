package com.finsight.devdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dev seeding on MySQL 8 (T-61, FR-02-4, Q-1): content, exactly one fraud alert, balances,
 * idempotency. The seeder runs once during context startup; tests read the result.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class DataSeederIT {

    private static final String DEMO_PASSWORD = "it-only-demo-password";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_seed_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("finsight.security.jwt.secret", () -> "data-seeder-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.seed.demo-password", () -> DEMO_PASSWORD);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSeeder seeder;
    @Autowired private PasswordEncoder passwordEncoder;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long demoUserId() {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, DataSeeder.DEMO_EMAIL);
        return id == null ? -1 : id;
    }

    @Test
    @DisplayName("One demo user with a BCrypt hash that matches the configured password (never the plaintext)")
    void demoUser() {
        assertThat(count("SELECT COUNT(*) FROM users")).isEqualTo(1);
        var hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, DataSeeder.DEMO_EMAIL);
        assertThat(hash).startsWith("$2").isNotEqualTo(DEMO_PASSWORD);
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("Two accounts with the design's opening balances; each balance = opening + its transactions")
    void accountsAndBalances() {
        long user = demoUserId();
        var rows = jdbc.queryForList("SELECT id, type, opening_balance, balance FROM accounts WHERE user_id = ? ORDER BY id", user);
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("CHECKING", "SAVINGS");
        assertThat((BigDecimal) rows.get(0).get("opening_balance")).isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) rows.get(1).get("opening_balance")).isEqualByComparingTo("12000.00");
        for (var r : rows) {
            var sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE account_id = ?",
                    BigDecimal.class, r.get("id"));
            assertThat((BigDecimal) r.get("balance"))
                    .isEqualByComparingTo(((BigDecimal) r.get("opening_balance")).add(sum));
        }
    }

    @Test
    @DisplayName("180 transactions over the last 90 days, all categories, country codes US/GB/CA only")
    void transactions() {
        assertThat(count("SELECT COUNT(*) FROM transactions")).isEqualTo(180);
        assertThat(count("SELECT COUNT(DISTINCT category) FROM transactions")).isEqualTo(7);
        assertThat(jdbc.queryForList("SELECT DISTINCT country_code FROM transactions", String.class))
                .containsExactlyInAnyOrder("US", "GB", "CA");
        var today = LocalDate.now();
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE transaction_date NOT BETWEEN ? AND ?",
                today.minusDays(89), today)).isZero();
    }

    @Test
    @DisplayName("Three budgets for the current period with the design's limits")
    void budgets() {
        var rows = jdbc.queryForList("SELECT category, monthly_limit FROM budgets ORDER BY category");
        assertThat(rows).extracting(r -> r.get("category")).containsExactly("DINING", "GROCERIES", "TRANSPORT");
        assertThat(rows).extracting(r -> ((BigDecimal) r.get("monthly_limit")).stripTrailingZeros().toPlainString())
                .containsExactly("300", "600", "200");
    }

    @Test
    @DisplayName("Exactly one fraud alert: ABNORMAL_AMOUNT on the planned large DINING payment, which meets the rule")
    void exactlyOneAbnormalAlert() {
        var alerts = jdbc.queryForList("SELECT rule_name, transaction_id, acknowledged FROM fraud_alerts");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().get("rule_name")).isEqualTo("ABNORMAL_AMOUNT");

        long txId = ((Number) alerts.getFirst().get("transaction_id")).longValue();
        var tx = jdbc.queryForMap("SELECT category, amount, transaction_date FROM transactions WHERE id = ?", txId);
        assertThat(tx.get("category")).isEqualTo("DINING");
        var amount = (BigDecimal) tx.get("amount");
        assertThat(amount).isEqualByComparingTo("-600.00");

        var today = LocalDate.now();
        var stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS s FROM transactions
                 WHERE category = 'DINING' AND id <> ? AND transaction_date BETWEEN ? AND ?
                """, txId, today.minusDays(29), today);
        long n = ((Number) stats.get("n")).longValue();
        var s = (BigDecimal) stats.get("s");
        assertThat(n).isGreaterThanOrEqualTo(5);
        assertThat(amount.abs().multiply(BigDecimal.valueOf(n))).isGreaterThan(s.abs().multiply(BigDecimal.valueOf(3)));
    }

    @Test
    @DisplayName("Forecast snapshots exist for all three horizons (D-9)")
    void snapshots() {
        assertThat(jdbc.queryForList("SELECT horizon_days FROM cash_flow_snapshots ORDER BY horizon_days", Integer.class))
                .containsExactly(30, 60, 90);
    }

    @Test
    @DisplayName("Running the seeder again changes nothing (empty-table guard)")
    void idempotent() {
        var tables = List.of("users", "accounts", "transactions", "budgets", "fraud_alerts", "cash_flow_snapshots");
        var before = tables.stream().map(t -> count("SELECT COUNT(*) FROM " + t)).toList();

        assertThat(seeder.seedIfEmpty()).isFalse();

        assertThat(tables.stream().map(t -> count("SELECT COUNT(*) FROM " + t)).toList()).isEqualTo(before);
    }
}
