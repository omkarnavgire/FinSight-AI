package com.finsight.budgeting;

import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.transactions.domain.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner scoping and idempotency of the budgeting repositories on MySQL 8 (V5 constraints).
 * {@code ddl-auto=validate} also proves {@code Budget}/{@code BudgetAlert} match V5.
 */
@SpringBootTest
@Testcontainers
class BudgetRepositoryIT {

    private static final long USER_A = 701L;
    private static final long USER_B = 702L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_budget_repo_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "budget-repo-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private BudgetRepository budgetRepository;
    @Autowired private BudgetAlertRepository alertRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private long budgetA;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM budget_alerts");
        jdbc.update("DELETE FROM budgets");
        budgetA = insertBudget(USER_A, "DINING", 2026, 3);
    }

    private long insertBudget(long userId, String category, int year, int month) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO budgets (user_id, category, monthly_limit, period_year, period_month) "
                    + "VALUES (?, ?, 100.00, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, category);
            ps.setInt(3, year);
            ps.setInt(4, month);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private int alertRows(long budgetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM budget_alerts WHERE budget_id = ?", Integer.class, budgetId);
    }

    @Test
    @DisplayName("findByIdAndUserId and the category/period finder are owner-scoped")
    void budgetQueries_ownerScoped() {
        assertThat(budgetRepository.findByIdAndUserId(budgetA, USER_A)).isPresent();
        assertThat(budgetRepository.findByIdAndUserId(budgetA, USER_B)).isEmpty();
        assertThat(budgetRepository.findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                USER_A, Category.DINING, (short) 2026, (byte) 3)).isPresent();
        assertThat(budgetRepository.findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                USER_B, Category.DINING, (short) 2026, (byte) 3)).isEmpty();
    }

    @Test
    @DisplayName("fireIfAbsent by another user inserts nothing")
    void fireIfAbsent_otherUser_noRow() {
        Integer inserted = tx.execute(s -> alertRepository.fireIfAbsent(budgetA, USER_B, 80));

        assertThat(inserted).isZero();
        assertThat(alertRows(budgetA)).isZero();
    }

    @Test
    @DisplayName("fireIfAbsent is idempotent: a repeat inserts nothing and leaves exactly one row")
    void fireIfAbsent_idempotent() {
        Integer first = tx.execute(s -> alertRepository.fireIfAbsent(budgetA, USER_A, 80));
        Integer second = tx.execute(s -> alertRepository.fireIfAbsent(budgetA, USER_A, 80));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(alertRows(budgetA)).isEqualTo(1);
    }

    @Test
    @DisplayName("firedThresholds is owner-scoped and ordered")
    void firedThresholds_ownerScoped() {
        tx.executeWithoutResult(s -> {
            alertRepository.fireIfAbsent(budgetA, USER_A, 100);
            alertRepository.fireIfAbsent(budgetA, USER_A, 80);
        });

        assertThat(alertRepository.firedThresholds(budgetA, USER_A)).extracting(Number::intValue).containsExactly(80, 100);
        assertThat(alertRepository.firedThresholds(budgetA, USER_B)).isEmpty();
    }

    @Test
    @DisplayName("deleteForBudget is owner-scoped")
    void deleteForBudget_ownerScoped() {
        tx.executeWithoutResult(s -> alertRepository.fireIfAbsent(budgetA, USER_A, 80));

        Integer deletedByOther = tx.execute(s -> alertRepository.deleteForBudget(budgetA, USER_B));
        assertThat(deletedByOther).isZero();
        assertThat(alertRows(budgetA)).isEqualTo(1);
        Integer deletedByOwner = tx.execute(s -> alertRepository.deleteForBudget(budgetA, USER_A));
        assertThat(deletedByOwner).isEqualTo(1);
        assertThat(alertRows(budgetA)).isZero();
    }

    @Test
    @DisplayName("The V5 unique key rejects a second budget for the same user, category and period")
    void uniqueBudgetKey_enforced() {
        assertThatThrownBy(() -> insertBudget(USER_A, "DINING", 2026, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertBudget(USER_B, "DINING", 2026, 3); // another user may have the same budget
    }
}
