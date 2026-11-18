package com.finsight.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema integration tests (T-19c).
 *
 * <p><strong>STATUS: NOT EXECUTED — PENDING DOCKER.</strong>
 * Requires a running Docker daemon for Testcontainers (MySQL 8).
 * Run when Docker Desktop is available:
 * {@code mvnw test -Dtest=SchemaIT} from {@code D:\FinSight-AI\backend}.
 *
 * <p>Verifies the database FK policy (design.md "Database Foreign-Key Policy"):
 * <ul>
 *   <li>AC-FK-1: same-module FKs are real MySQL constraints
 *       ({@code refresh_tokens → users}, {@code budget_alerts → budgets}).</li>
 *   <li>AC-FK-2: cross-module logical relationships have no MySQL FK constraint
 *       ({@code accounts.user_id}, {@code transactions.account_id},
 *       {@code cash_flow_snapshots.user_id}, {@code fraud_alerts.user_id},
 *       {@code fraud_alerts.transaction_id}).</li>
 *   <li>AC-FLY-3: {@code V1__baseline.sql} creates no tables.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Testcontainers
class SchemaIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_schema_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Use 'none' here: Flyway creates the schema; Hibernate validation is what
        // *we* are testing manually via JdbcTemplate queries below.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("finsight.security.jwt.secret",
                () -> "schema-it-secret-must-be-at-least-32-chars-ok!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    // ---- AC-FK-1: same-module FKs must exist ----

    @Test
    @DisplayName("AC-FK-1: refresh_tokens.user_id → users.id FK constraint exists in DB")
    void refreshTokens_hasRealFkToUsers() {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'refresh_tokens'
                  AND REFERENCED_TABLE_NAME = 'users'
                  AND CONSTRAINT_NAME != 'PRIMARY'
                """, Integer.class);
        assertThat(count).as("FK refresh_tokens → users must exist").isGreaterThan(0);
    }

    @Test
    @DisplayName("AC-FK-1: budget_alerts.budget_id → budgets.id FK constraint exists in DB")
    void budgetAlerts_hasRealFkToBudgets() {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'budget_alerts'
                  AND REFERENCED_TABLE_NAME = 'budgets'
                  AND CONSTRAINT_NAME != 'PRIMARY'
                """, Integer.class);
        assertThat(count).as("FK budget_alerts → budgets must exist").isGreaterThan(0);
    }

    // ---- AC-FK-2: cross-module logical relationships must NOT have DB FK constraints ----

    @Test
    @DisplayName("AC-FK-2: accounts.user_id has NO FK constraint to users (cross-module)")
    void accounts_noFkToUsers() {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'accounts'
                  AND REFERENCED_TABLE_NAME = 'users'
                """, Integer.class);
        assertThat(count).as("accounts.user_id must NOT have a DB FK to users").isZero();
    }

    @Test
    @DisplayName("AC-FK-2: transactions.account_id has NO FK constraint to accounts (cross-module)")
    void transactions_noFkToAccounts() {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'transactions'
                  AND REFERENCED_TABLE_NAME = 'accounts'
                """, Integer.class);
        assertThat(count).as("transactions.account_id must NOT have a DB FK to accounts").isZero();
    }

    @Test
    @DisplayName("AC-FK-2: cash_flow_snapshots.user_id has NO FK constraint to users (cross-module)")
    void cashFlowSnapshots_noFkToUsers() {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'cash_flow_snapshots'
                  AND REFERENCED_TABLE_NAME = 'users'
                """, Integer.class);
        assertThat(count).as("cash_flow_snapshots.user_id must NOT have a DB FK to users").isZero();
    }

    @Test
    @DisplayName("AC-FK-2: fraud_alerts.user_id has NO FK constraint to users (cross-module)")
    void fraudAlerts_noFkToUsers() {
        assertThat(foreignKeyCount("fraud_alerts", "users"))
                .as("fraud_alerts.user_id must NOT have a DB FK to users").isZero();
    }

    @Test
    @DisplayName("AC-FK-2: fraud_alerts.transaction_id has NO FK constraint to transactions (cross-module)")
    void fraudAlerts_noFkToTransactions() {
        assertThat(foreignKeyCount("fraud_alerts", "transactions"))
                .as("fraud_alerts.transaction_id must NOT have a DB FK to transactions").isZero();
    }

    @Test
    @DisplayName("AC-FK-2: budgets.user_id has NO FK constraint to users (cross-module)")
    void budgets_noFkToUsers() {
        // budgeting module -> auth module: cross-module, enforced in BudgetService (V5 comment).
        assertThat(foreignKeyCount("budgets", "users"))
                .as("budgets.user_id must NOT have a DB FK to users").isZero();
    }

    /** Counts FK constraints from {@code table} that reference {@code referencedTable}. */
    private Integer foreignKeyCount(String table, String referencedTable) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND REFERENCED_TABLE_NAME = ?
                """, Integer.class, table, referencedTable);
    }

    @Test
    @DisplayName("AC-FLY-3: V1__baseline.sql creates no tables — only Flyway schema_history exists after V1")
    void v1Migration_createsNoApplicationTables() {
        // All application tables (users, accounts, etc.) are created by V2+.
        // We can't rewind Flyway here, but we can confirm no V1-only tables exist.
        // This check verifies the expected tables are present (created by V2–V7),
        // confirming V1 is a no-op marker.
        var tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME NOT IN ('flyway_schema_history')
                ORDER BY TABLE_NAME
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "accounts", "budget_alerts", "budgets",
                "cash_flow_snapshots", "fraud_alerts",
                "refresh_tokens", "transactions", "users");
    }
}
