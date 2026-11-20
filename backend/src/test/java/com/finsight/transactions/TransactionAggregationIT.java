package com.finsight.transactions;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CategorySpendingDto;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.DatedAmountDto;
import com.finsight.transactions.dto.MonthSpendingDto;
import com.finsight.transactions.service.TransactionAggregationService;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Phase 7 aggregation queries of {@link TransactionAggregationService} on MySQL 8: native projection
 * mapping, debit-only filter, inclusive date bounds, ownership, ordering and month grouping.
 * Totals are cross-checked with raw SQL.
 */
@SpringBootTest
@Testcontainers
class TransactionAggregationIT {

    private static final AtomicLong USERS = new AtomicLong(7000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_tx_agg_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "tx-aggregation-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionAggregationService aggregationService;
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

    private void tx(long accountId, String amount, String date, Category category) {
        transactionService.create(new CreateTransactionRequest(
                accountId, new BigDecimal(amount), LocalDate.parse(date), category, "it", "US"));
    }

    private BigDecimal rawDebitTotal(long userId, String from, String to) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(ABS(t.amount)), 0)
                  FROM transactions t JOIN accounts a ON a.id = t.account_id
                 WHERE a.user_id = ? AND t.amount < 0 AND t.transaction_date BETWEEN ? AND ?
                """, BigDecimal.class, userId, from, to);
    }

    private static BigDecimal sum(List<CategorySpendingDto> rows) {
        return rows.stream().map(CategorySpendingDto::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("Category totals: debits only as absolute values, both bounds inclusive, ordered by total then name")
    void byCategory_debitsInclusiveOrdered() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        tx(acc, "-10.00", "2026-03-01", Category.DINING);      // first day
        tx(acc, "-15.25", "2026-03-31", Category.DINING);      // last day
        tx(acc, "-25.25", "2026-03-15", Category.GROCERIES);   // ties DINING, name decides
        tx(acc, "-40.00", "2026-03-10", Category.TRANSPORT);
        tx(acc, "900.00", "2026-03-05", Category.INCOME);      // credit, excluded
        tx(acc, "-99.00", "2026-02-28", Category.DINING);      // before range
        tx(acc, "-99.00", "2026-04-01", Category.DINING);      // after range

        var result = aggregationService.debitTotalsByCategory(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(result).extracting(CategorySpendingDto::category)
                .containsExactly(Category.TRANSPORT, Category.DINING, Category.GROCERIES);
        assertThat(result).extracting(CategorySpendingDto::total)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("25.25"), new BigDecimal("25.25"));
        assertThat(sum(result)).isEqualByComparingTo(rawDebitTotal(user, "2026-03-01", "2026-03-31"));
    }

    @Test
    @DisplayName("Transactions on a deactivated account are still counted")
    void byCategory_deactivatedAccountCounted() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        tx(acc, "-12.00", "2026-03-10", Category.UTILITIES);
        accountService.deactivate(acc);

        var result = aggregationService.debitTotalsByCategory(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(result).extracting(CategorySpendingDto::category).containsExactly(Category.UTILITIES);
        assertThat(sum(result)).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("Another user's spending is never included")
    void byCategory_otherUserExcluded() {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long accA = openAccount(a);
        tx(accA, "-30.00", "2026-03-10", Category.DINING);
        long accB = openAccount(b);
        tx(accB, "-500.00", "2026-03-10", Category.DINING);
        tx(accB, "-70.00", "2026-03-11", Category.OTHER);

        actAs(a);
        var result = aggregationService.debitTotalsByCategory(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(result).extracting(CategorySpendingDto::category).containsExactly(Category.DINING);
        assertThat(sum(result)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("A user with no debits in the range gets empty lists")
    void empty() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        tx(acc, "100.00", "2026-03-10", Category.INCOME);

        var from = LocalDate.of(2026, 3, 1);
        var to = LocalDate.of(2026, 3, 31);
        assertThat(aggregationService.debitTotalsByCategory(from, to)).isEmpty();
        assertThat(aggregationService.debitTotalsByMonth(from, to)).isEmpty();
    }

    @Test
    @DisplayName("Month totals group by calendar month across December to January, oldest first")
    void byMonth_groupsAcrossYearEnd() {
        long user = USERS.incrementAndGet();
        long acc = openAccount(user);
        tx(acc, "-10.00", "2025-12-01", Category.DINING);
        tx(acc, "-5.50", "2025-12-31", Category.OTHER);
        tx(acc, "-20.00", "2026-01-01", Category.DINING);
        tx(acc, "250.00", "2026-01-15", Category.INCOME);   // credit, excluded
        tx(acc, "-7.00", "2026-03-31", Category.TRANSPORT); // February has none

        var result = aggregationService.debitTotalsByMonth(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 3, 31));

        assertThat(result).extracting(MonthSpendingDto::year, MonthSpendingDto::month)
                .containsExactly(tuple(2025, 12), tuple(2026, 1), tuple(2026, 3));
        assertThat(result).extracting(MonthSpendingDto::total)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("15.50"), new BigDecimal("20.00"), new BigDecimal("7.00"));
        var monthSum = result.stream().map(MonthSpendingDto::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(monthSum).isEqualByComparingTo(rawDebitTotal(user, "2025-12-01", "2026-03-31"));
    }

    @Test
    @DisplayName("A 367-day range is rejected with 400 on the real bean; 366 days runs")
    void rangeLimit_onRealBean() {
        long user = USERS.incrementAndGet();
        actAs(user);

        assertThatThrownBy(() -> aggregationService.debitTotalsByMonth(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)))
                .isInstanceOf(BadRequestException.class);
        assertThat(aggregationService.debitTotalsByMonth(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))).isEmpty();
    }

    // ---- dated amounts (Phase 8) ----

    @Test
    @DisplayName("Dated amounts: credits and debits with sign, both bounds inclusive, oldest first, other users excluded")
    void amountsBetween_signedInclusiveOwnerScoped() {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long accA = openAccount(a);
        tx(accA, "-20.00", "2026-03-31", Category.DINING);     // last day
        tx(accA, "3000.00", "2026-03-01", Category.INCOME);    // first day, credit kept
        tx(accA, "-99.00", "2026-02-28", Category.OTHER);      // before range
        tx(accA, "-99.00", "2026-04-01", Category.OTHER);      // after range
        long accB = openAccount(b);
        tx(accB, "-500.00", "2026-03-10", Category.DINING);    // other user

        actAs(a);
        var result = aggregationService.amountsBetween(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(result).extracting(DatedAmountDto::date)
                .containsExactly(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(result).extracting(DatedAmountDto::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("3000.00"), new BigDecimal("-20.00"));
    }
}
