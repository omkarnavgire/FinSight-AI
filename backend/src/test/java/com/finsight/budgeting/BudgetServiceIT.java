package com.finsight.budgeting;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.dto.UpdateBudgetRequest;
import com.finsight.budgeting.service.BudgetService;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end budgeting on MySQL 8: event-driven alerts from real transactions, spending rules,
 * historical progress, deletion and ownership. Alerts and budgets are read back with raw SQL.
 */
@SpringBootTest
@Testcontainers
class BudgetServiceIT {

    private static final AtomicLong USERS = new AtomicLong(800);
    private static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_budget_service_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "budget-service-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private BudgetService budgetService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static long newUser() {
        long id = USERS.incrementAndGet();
        actAs(id);
        return id;
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private long account() {
        return accountService.create(new CreateAccountRequest("IT", AccountType.CHECKING, new BigDecimal("5000.00"), null)).id();
    }

    private long budget(Category category, String limit, int year, int month) {
        return budgetService.create(new CreateBudgetRequest(category, new BigDecimal(limit), year, month)).id();
    }

    private long spend(long accountId, Category category, String amount, LocalDate date) {
        return transactionService.create(new CreateTransactionRequest(
                accountId, new BigDecimal(amount), date, category, "it", "US")).id();
    }

    private List<Integer> alertsInDb(long budgetId) {
        return jdbc.queryForList("SELECT threshold_pct FROM budget_alerts WHERE budget_id = ? ORDER BY threshold_pct",
                Integer.class, budgetId);
    }

    @Test
    @DisplayName("Alerts fire from transaction events alone: -50 none, -30 → 80, -20 → 80 and 100")
    void eventDriven_thresholdSequence() {
        newUser();
        long acc = account();
        long b = budget(Category.DINING, "100.00", 2026, 3);

        spend(acc, Category.DINING, "-50.00", MARCH_10);
        assertThat(alertsInDb(b)).isEmpty();

        spend(acc, Category.DINING, "-30.00", MARCH_10);
        assertThat(alertsInDb(b)).containsExactly(80);

        spend(acc, Category.DINING, "-20.00", MARCH_10);
        assertThat(alertsInDb(b)).containsExactly(80, 100);
    }

    @Test
    @DisplayName("Spending counts debits only, in the category and month; deactivated accounts are included")
    void spendingRules() {
        newUser();
        long acc = account();
        long other = account();
        long b = budget(Category.DINING, "100.00", 2026, 3);
        spend(acc, Category.DINING, "-40.00", MARCH_10);
        spend(acc, Category.DINING, "30.00", MARCH_10);                    // credit: ignored
        spend(acc, Category.GROCERIES, "-500.00", MARCH_10);               // other category
        spend(acc, Category.DINING, "-500.00", LocalDate.of(2026, 4, 1));  // other month
        spend(other, Category.DINING, "-10.00", LocalDate.of(2026, 3, 31));
        accountService.deactivate(other);

        var p = budgetService.getProgress(b, "2026", "3");

        assertThat(p.spent()).isEqualByComparingTo("50.00");
        assertThat(p.percentUsed()).isEqualByComparingTo("50.00");
        assertThat(p.alertsFired()).isEmpty();
    }

    @Test
    @DisplayName("Historical progress (?year=2025&month=3) uses that month's spending and reports no alerts")
    void historicalProgress() {
        newUser();
        long acc = account();
        long b = budget(Category.DINING, "100.00", 2026, 3);
        spend(acc, Category.DINING, "-90.00", MARCH_10);
        spend(acc, Category.DINING, "-40.00", LocalDate.of(2025, 3, 5));

        var historical = budgetService.getProgress(b, "2025", "3");
        var own = budgetService.getProgress(b, "2026", "3");

        assertThat(historical.year()).isEqualTo(2025);
        assertThat(historical.spent()).isEqualByComparingTo("40.00");
        assertThat(historical.alertsFired()).isEmpty();
        assertThat(own.spent()).isEqualByComparingTo("90.00");
        assertThat(own.alertsFired()).containsExactly(80);
    }

    @Test
    @DisplayName("Raising the limit keeps fired alerts; lowering it fires new ones after commit")
    void limitChange_reEvaluates() {
        newUser();
        long acc = account();
        long b = budget(Category.DINING, "100.00", 2026, 3);
        spend(acc, Category.DINING, "-85.00", MARCH_10);
        assertThat(alertsInDb(b)).containsExactly(80);

        budgetService.updateLimit(b, new UpdateBudgetRequest(new BigDecimal("1000.00")));
        assertThat(alertsInDb(b)).containsExactly(80);

        budgetService.updateLimit(b, new UpdateBudgetRequest(new BigDecimal("80.00")));
        assertThat(alertsInDb(b)).containsExactly(80, 100);
    }

    @Test
    @DisplayName("Deleting a budget removes its alerts and the budget (FK respected)")
    void delete_removesAlerts() {
        newUser();
        long acc = account();
        long b = budget(Category.DINING, "10.00", 2026, 3);
        spend(acc, Category.DINING, "-20.00", MARCH_10);
        assertThat(alertsInDb(b)).containsExactly(80, 100);

        budgetService.delete(b);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE id = ?", Integer.class, b)).isZero();
        assertThat(alertsInDb(b)).isEmpty();
    }

    @Test
    @DisplayName("User B cannot read progress, update or delete user A's budget; A's data is unchanged")
    void otherUser_notFound() {
        long userA = newUser();
        long acc = account();
        long b = budget(Category.DINING, "100.00", 2026, 3);
        spend(acc, Category.DINING, "-90.00", MARCH_10);

        newUser();
        assertThatThrownBy(() -> budgetService.getProgress(b, null, null)).isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> budgetService.updateLimit(b, new UpdateBudgetRequest(BigDecimal.ONE)))
                .isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> budgetService.delete(b)).isInstanceOf(BudgetNotFoundException.class);

        var row = jdbc.queryForMap("SELECT user_id, monthly_limit FROM budgets WHERE id = ?", b);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userA);
        assertThat((BigDecimal) row.get("monthly_limit")).isEqualByComparingTo("100.00");
        assertThat(alertsInDb(b)).containsExactly(80);
    }

    @Test
    @DisplayName("Another user's spending never counts toward a budget")
    void otherUsersSpending_ignored() {
        newUser();
        long b = budget(Category.DINING, "100.00", 2026, 3);

        newUser();
        long accB = account();
        spend(accB, Category.DINING, "-500.00", MARCH_10);

        assertThat(alertsInDb(b)).isEmpty();
    }
}
