package com.finsight.transactions;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.TransactionNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.UpdateTransactionRequest;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * {@link TransactionService} on MySQL 8 (T-30b): atomicity, rollback, balance consistency,
 * concurrency and the persistence-context rule. Balances are always read back with raw SQL.
 *
 * <p>{@link AccountService} is a Mockito spy so single tests can force a failure; it behaves
 * normally unless stubbed, and is reset after each test.
 *
 * <p>The T-30b case "fraud-alert insert failure rolls back transaction and balance" needs Phase 9
 * code and is covered by T-58 (decision D-2).
 */
@SpringBootTest
@Testcontainers
class TransactionServiceIT {

    private static final long USER_A = 501L;
    private static final long USER_B = 502L;
    private static final LocalDate DAY = LocalDate.of(2026, 5, 10);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_tx_service_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "tx-service-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private TransactionService transactionService;
    @MockitoSpyBean private AccountService accountService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    /**
     * The Mockito spy sits behind Spring's transactional proxy. Stubbing must target the spy itself:
     * calling {@code doThrow().when(proxy).applyDelta(...)} would run the {@code MANDATORY} transaction
     * interceptor during stubbing. Production calls still go through the proxy, so transaction
     * semantics are unchanged.
     */
    private AccountService spyTarget() {
        return AopTestUtils.getUltimateTargetObject(accountService);
    }

    private long openAccount(long owner, String opening) {
        actAs(owner);
        return accountService.create(new CreateAccountRequest("IT", AccountType.CHECKING, new BigDecimal(opening), null)).id();
    }

    private long create(long accountId, String amount) {
        return transactionService.create(new CreateTransactionRequest(
                accountId, new BigDecimal(amount), DAY, Category.OTHER, "it", "US")).id();
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    private BigDecimal openingBalance(long accountId) {
        return jdbc.queryForObject("SELECT opening_balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    private BigDecimal sumOfAmounts(long accountId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE account_id = ?",
                BigDecimal.class, accountId);
    }

    private int rowCount(long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId);
    }

    /** NFR-03-3 / AC-BAL-7: balance = opening_balance + SUM(amount), read with raw SQL. */
    private void assertBalanceInvariant(long accountId) {
        assertThat(balance(accountId))
                .as("balance must equal opening_balance + SUM(transaction amounts)")
                .isEqualByComparingTo(openingBalance(accountId).add(sumOfAmounts(accountId)));
    }

    // ---- balance consistency ----

    @Test
    @DisplayName("Create, edit and delete keep balance = opening + SUM(amount); opening never changes")
    void createEditDelete_keepInvariant() {
        long acc = openAccount(USER_A, "1000.00");

        long tx = create(acc, "-50.00");                                  // AC-BAL-2
        assertThat(balance(acc)).isEqualByComparingTo("950.00");
        assertBalanceInvariant(acc);

        transactionService.update(tx, new UpdateTransactionRequest(       // AC-BAL-3
                new BigDecimal("-80.00"), DAY, Category.DINING, "edited"));
        assertThat(balance(acc)).isEqualByComparingTo("920.00");
        assertBalanceInvariant(acc);

        create(acc, "200.00");
        assertThat(balance(acc)).isEqualByComparingTo("1120.00");
        assertBalanceInvariant(acc);

        transactionService.delete(tx);                                     // AC-BAL-4
        assertThat(balance(acc)).isEqualByComparingTo("1200.00");
        assertBalanceInvariant(acc);

        assertThat(openingBalance(acc)).isEqualByComparingTo("1000.00");  // AC-BAL-7
    }

    // ---- rollback (T-30b, AC-BAL-5) ----

    @Test
    @DisplayName("Forced failure in the balance update rolls back the transaction INSERT")
    void balanceUpdateFailure_rollsBackInsert() {
        long acc = openAccount(USER_A, "500.00");
        doThrow(new IllegalStateException("simulated balance update failure"))
                .when(spyTarget()).applyDelta(anyLong(), any());

        assertThatThrownBy(() -> create(acc, "-75.00")).isInstanceOf(IllegalStateException.class);

        assertThat(rowCount(acc)).as("no transaction row may survive").isZero();
        assertThat(balance(acc)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Forced failure in the balance update during edit rolls back the edit")
    void balanceUpdateFailure_rollsBackEdit() {
        long acc = openAccount(USER_A, "500.00");
        long tx = create(acc, "-10.00");
        doThrow(new IllegalStateException("simulated")).when(spyTarget()).applyDelta(anyLong(), any());

        assertThatThrownBy(() -> transactionService.update(tx, new UpdateTransactionRequest(
                new BigDecimal("-99.00"), DAY, Category.OTHER, "x"))).isInstanceOf(IllegalStateException.class);

        var amount = jdbc.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, tx);
        assertThat(amount).isEqualByComparingTo("-10.00");
        assertThat(balance(acc)).isEqualByComparingTo("490.00");
    }

    @Test
    @DisplayName("Account deactivated after the early check: guarded update gives 409 and the INSERT rolls back")
    void deactivatedAfterCheck_guardRollsBack() {
        long acc = openAccount(USER_A, "100.00");
        doAnswer(inv -> {
            var result = inv.callRealMethod();
            // Same DB transaction: deactivate between requireOwnedActive and applyDelta.
            jdbc.update("UPDATE accounts SET active = FALSE WHERE id = ?", acc);
            return result;
        }).when(spyTarget()).requireOwnedActive(acc);

        assertThatThrownBy(() -> create(acc, "-5.00")).isInstanceOf(AccountDeactivatedException.class);

        assertThat(rowCount(acc)).isZero();
        assertThat(balance(acc)).isEqualByComparingTo("100.00");
    }

    // ---- ownership ----

    @Test
    @DisplayName("User B cannot edit, delete or re-categorise user A's transaction; A's data is unchanged")
    void otherUser_cannotTouchTransaction() {
        long acc = openAccount(USER_A, "300.00");
        long tx = create(acc, "-30.00");

        actAs(USER_B);
        assertThatThrownBy(() -> transactionService.update(tx, new UpdateTransactionRequest(
                new BigDecimal("-1.00"), DAY, Category.OTHER, null))).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> transactionService.delete(tx)).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> transactionService.overrideCategory(tx, Category.INCOME))
                .isInstanceOf(TransactionNotFoundException.class);

        var row = jdbc.queryForMap("SELECT amount, category FROM transactions WHERE id = ?", tx);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-30.00");
        assertThat(row.get("category")).isEqualTo("OTHER");
        assertThat(balance(acc)).isEqualByComparingTo("270.00");
    }

    // ---- persistence-context rule ----

    @Test
    @DisplayName("Fields changed before applyDelta are persisted despite the persistence-context clear")
    void editedFields_persistedDespiteContextClear() {
        long acc = openAccount(USER_A, "0.00");
        long tx = create(acc, "-12.00");

        transactionService.update(tx, new UpdateTransactionRequest(
                new BigDecimal("-15.50"), LocalDate.of(2026, 6, 1), Category.DINING, "after edit"));

        var row = jdbc.queryForMap(
                "SELECT amount, transaction_date, category, description FROM transactions WHERE id = ?", tx);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-15.50");
        assertThat(row.get("transaction_date").toString()).isEqualTo("2026-06-01");
        assertThat(row.get("category")).isEqualTo("DINING");
        assertThat(row.get("description")).isEqualTo("after edit");
        assertBalanceInvariant(acc);
    }

    @Test
    @DisplayName("Category override is persisted and does not change the balance")
    void categoryOverride_persisted_noBalanceChange() {
        long acc = openAccount(USER_A, "50.00");
        long tx = create(acc, "-5.00");

        transactionService.overrideCategory(tx, Category.ENTERTAINMENT);

        assertThat(jdbc.queryForObject("SELECT category FROM transactions WHERE id = ?", String.class, tx))
                .isEqualTo("ENTERTAINMENT");
        assertThat(balance(acc)).isEqualByComparingTo("45.00");
    }

    // ---- concurrency ----

    private <T> List<T> runConcurrently(long userId, List<Callable<T>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<T>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    actAs(userId); // SecurityContext is per thread
                    try {
                        start.await();
                        return task.call();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Concurrent edits of one transaction are serialised: no double counting")
    void concurrentEdits_noDoubleCount() throws Exception {
        long acc = openAccount(USER_A, "1000.00");
        for (int round = 0; round < 5; round++) {
            actAs(USER_A);
            long tx = create(acc, "-50.00");
            runConcurrently(USER_A, List.of(
                    () -> transactionService.update(tx, new UpdateTransactionRequest(
                            new BigDecimal("-80.00"), DAY, Category.OTHER, "t1")),
                    () -> transactionService.update(tx, new UpdateTransactionRequest(
                            new BigDecimal("-120.00"), DAY, Category.OTHER, "t2"))));
            assertBalanceInvariant(acc);
        }
    }

    @Test
    @DisplayName("Concurrent creates on one account: balance = opening + all amounts")
    void concurrentCreates_allApplied() throws Exception {
        long acc = openAccount(USER_A, "0.00");
        var tasks = new ArrayList<Callable<Long>>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> create(acc, "-10.00"));
        }

        runConcurrently(USER_A, tasks);

        assertThat(rowCount(acc)).isEqualTo(8);
        assertThat(balance(acc)).isEqualByComparingTo("-80.00");
        assertBalanceInvariant(acc);
    }

    @Test
    @DisplayName("Concurrent delete and edit of one transaction leave a consistent balance")
    void concurrentDeleteAndEdit_consistent() throws Exception {
        long acc = openAccount(USER_A, "100.00");
        long tx = create(acc, "-40.00");

        // One of the two may lose the race with a 404 (row already deleted); both outcomes are valid.
        var outcomes = runConcurrently(USER_A, List.<Callable<String>>of(
                () -> { transactionService.delete(tx); return "deleted"; },
                () -> {
                    try {
                        transactionService.update(tx, new UpdateTransactionRequest(
                                new BigDecimal("-60.00"), DAY, Category.OTHER, null));
                        return "edited";
                    } catch (TransactionNotFoundException e) {
                        return "not-found";
                    }
                }));

        assertThat(outcomes).contains("deleted");
        assertThat(rowCount(acc)).isZero();
        assertThat(balance(acc)).isEqualByComparingTo("100.00");
        assertBalanceInvariant(acc);
    }
}
