package com.finsight.fraud;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end fraud detection on MySQL 8 (T-58): creating a transaction through
 * {@link TransactionService#create} stores the right alerts, and a failing alert INSERT rolls back
 * the transaction row, the balance change and every alert of that call (AC-ORD-1, AC-ORD-2, C-3).
 *
 * <p>Duplicate cases keep timestamps far from the 60 s boundary (DATETIME has whole-second
 * precision); the exact boundary is covered by {@code DuplicateTransactionRuleTest} (C-1).
 */
@SpringBootTest
@Testcontainers
class FraudDetectionServiceIT {

    private static final AtomicLong USERS = new AtomicLong(12000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_fraud_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "fraud-detection-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private long openAccount(long owner) {
        actAs(owner);
        return accountService.create(new CreateAccountRequest("IT", AccountType.CHECKING, new BigDecimal("5000.00"), null)).id();
    }

    private long create(long accountId, String amount, Category category, String description, String country) {
        return transactionService.create(new CreateTransactionRequest(
                accountId, new BigDecimal(amount), LocalDate.now(), category, description, country)).id();
    }

    private List<String> rulesFor(long transactionId) {
        return jdbc.queryForList("SELECT rule_name FROM fraud_alerts WHERE transaction_id = ? ORDER BY id",
                String.class, transactionId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Duplicate within 60 s stores a DUPLICATE_TRANSACTION alert owned by the user, for the new transaction")
    void duplicate_storesAlert() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        long first = create(acc, "-30.00", Category.DINING, "Pizza", "US");
        long second = create(acc, "-30.00", Category.DINING, "Pizza", "US");

        assertThat(rulesFor(first)).isEmpty();
        assertThat(rulesFor(second)).containsExactly("DUPLICATE_TRANSACTION");
        assertThat(count("SELECT COUNT(*) FROM fraud_alerts WHERE transaction_id = ? AND user_id = ? AND severity = 'HIGH'",
                second, user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE id = ?", second))
                .as("AC-ORD-1: the alert references an existing transaction").isEqualTo(1);
    }

    @Test
    @DisplayName("A match created long before the window (10 minutes) is not a duplicate")
    void oldMatch_noDuplicate() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        long first = create(acc, "-30.00", Category.DINING, "Pizza", "US");
        jdbc.update("UPDATE transactions SET created_at = DATE_SUB(created_at, INTERVAL 10 MINUTE) WHERE id = ?", first);

        long second = create(acc, "-30.00", Category.DINING, "Pizza", "US");

        assertThat(rulesFor(second)).isEmpty();
    }

    @Test
    @DisplayName("Unseen country: skipped on the first-ever transaction, fires for a new country, quiet for a known one")
    void unseenCountry() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        long first = create(acc, "-5.00", Category.OTHER, "a", "GB");
        long newCountry = create(acc, "-6.00", Category.OTHER, "b", "US");
        long known = create(acc, "-7.00", Category.OTHER, "c", "US");

        assertThat(rulesFor(first)).isEmpty();
        assertThat(rulesFor(newCountry)).containsExactly("UNSEEN_COUNTRY");
        assertThat(rulesFor(known)).isEmpty();
    }

    @Test
    @DisplayName("Abnormal amount: skipped with 4 category transactions, fires with 5 and an amount above 3 x mean")
    void abnormalAmount() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        for (int i = 1; i <= 4; i++) {
            create(acc, "-10.00", Category.GROCERIES, "shop " + i, "US");
        }
        long withFour = create(acc, "-100.00", Category.GROCERIES, "big 1", "US");   // 4 prior: skipped
        long withFive = create(acc, "-500.00", Category.GROCERIES, "big 2", "US");   // 5 prior: mean -28 -> fires

        assertThat(rulesFor(withFour)).isEmpty();
        assertThat(rulesFor(withFive)).containsExactly("ABNORMAL_AMOUNT");
    }

    @Test
    @DisplayName("T-58: second alert INSERT fails -> transaction row, balance change and all alerts roll back")
    void alertInsertFailure_rollsBackEverything() throws SQLException {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        create(acc, "-25.00", Category.DINING, "Coffee", "US");            // first-ever: no alerts
        var balanceBefore = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, acc);
        int txBefore = count("SELECT COUNT(*) FROM transactions WHERE account_id = ?", acc);
        int alertsBefore = count("SELECT COUNT(*) FROM fraud_alerts WHERE user_id = ?", user);

        // Same account, amount and description within 60 s (DUPLICATE_TRANSACTION, inserted first)
        // and a new country (UNSEEN_COUNTRY, inserted second). The trigger rejects the second INSERT.
        createFailingTrigger(user);
        try {
            assertThatThrownBy(() -> create(acc, "-25.00", Category.DINING, "Coffee", "GB"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            dropFailingTrigger();
        }

        assertThat(count("SELECT COUNT(*) FROM fraud_alerts WHERE user_id = ?", user))
                .as("no alert of the failed call survives, including the first one").isEqualTo(alertsBefore);
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE account_id = ?", acc))
                .as("the transaction row is rolled back").isEqualTo(txBefore);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, acc))
                .as("the balance change is rolled back").isEqualByComparingTo(balanceBefore);
    }

    /** Created as root: CREATE TRIGGER needs privileges the application user may lack with binary logging on. */
    private static void createFailingTrigger(long userId) throws SQLException {
        rootExecute("""
                CREATE TRIGGER it_fail_second_alert BEFORE INSERT ON fraud_alerts FOR EACH ROW
                BEGIN
                  IF NEW.user_id = %d AND NEW.rule_name = 'UNSEEN_COUNTRY' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'simulated alert insert failure';
                  END IF;
                END
                """.formatted(userId));
    }

    private static void dropFailingTrigger() throws SQLException {
        rootExecute("DROP TRIGGER IF EXISTS it_fail_second_alert");
    }

    private static void rootExecute(String sql) throws SQLException {
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
