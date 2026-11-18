package com.finsight.budgeting;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.service.BudgetAlertService;
import com.finsight.budgeting.service.BudgetService;
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
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Budget-alert races and recovery on MySQL 8 (Phase 6 plan, R-A to R-H).
 *
 * <p>Latches hold one transaction open while another commits, so each interleaving is deterministic.
 * Alert rows are always read with raw SQL. {@link BudgetAlertService} is a Mockito spy that behaves
 * normally unless one test stubs it; stubbing targets the spy behind the transactional proxy.
 */
@SpringBootTest
@Testcontainers
class BudgetAlertRecoveryIT {

    private static final AtomicLong USERS = new AtomicLong(900);
    private static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);
    private static final long WAIT_SECONDS = 60;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_budget_recovery_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "budget-recovery-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private BudgetService budgetService;
    @MockitoSpyBean private BudgetAlertService alertService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- helpers ----

    private static long newUser() {
        long id = USERS.incrementAndGet();
        actAs(id);
        return id;
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private BudgetAlertService spyTarget() {
        return AopTestUtils.getUltimateTargetObject(alertService);
    }

    private long account() {
        return accountService.create(new CreateAccountRequest("IT", AccountType.CHECKING, new BigDecimal("5000.00"), null)).id();
    }

    private long createBudget(Category category, String limit, int year, int month) {
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Runs a task in a new thread with the given user in its (thread-local) security context. */
    private static <T> Future<T> inThread(long userId, Callable<T> task) {
        var pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> {
                actAs(userId);
                try {
                    return task.call();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        } finally {
            pool.shutdown();
        }
    }

    // ---- R-A ----

    @Test
    @DisplayName("R-A: transaction commits first, budget created later → budget's after-commit evaluation fires 80")
    void transactionFirst_thenBudget() {
        newUser();
        long acc = account();
        spend(acc, Category.DINING, "-90.00", MARCH_10);

        long b = createBudget(Category.DINING, "100.00", 2026, 3);

        assertThat(alertsInDb(b)).containsExactly(80);
    }

    // ---- R-B ----

    @Test
    @DisplayName("R-B: budget transaction open while a transaction commits → alert fires when the budget commits")
    void budgetOpen_transactionCommits() throws Exception {
        long user = newUser();
        long acc = account();
        var budgetInserted = new CountDownLatch(1);
        var spendCommitted = new CountDownLatch(1);

        Future<Long> budgetTx = inThread(user, () -> tx.execute(s -> {
            long id = createBudget(Category.DINING, "100.00", 2026, 3);
            budgetInserted.countDown();
            await(spendCommitted);          // budget still uncommitted
            return id;
        }));

        await(budgetInserted);
        spend(acc, Category.DINING, "-90.00", MARCH_10);   // its listener cannot see the uncommitted budget
        spendCommitted.countDown();

        long b = budgetTx.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(alertsInDb(b)).as("fired by the budget's after-commit evaluation").containsExactly(80);
    }

    // ---- R-C ----

    @Test
    @DisplayName("R-C: transaction open while the budget commits → alert fires when the transaction commits")
    void transactionOpen_budgetCommits() throws Exception {
        long user = newUser();
        long acc = account();
        var spendInserted = new CountDownLatch(1);
        var budgetCommitted = new CountDownLatch(1);

        Future<Object> spendTx = inThread(user, () -> tx.execute(s -> {
            spend(acc, Category.DINING, "-90.00", MARCH_10);
            spendInserted.countDown();
            await(budgetCommitted);         // transaction still uncommitted
            return null;
        }));

        await(spendInserted);
        long b = createBudget(Category.DINING, "100.00", 2026, 3);
        assertThat(alertsInDb(b)).as("budget evaluation cannot see the uncommitted spending").isEmpty();
        budgetCommitted.countDown();

        spendTx.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(alertsInDb(b)).as("fired by the transaction's after-commit evaluation").containsExactly(80);
    }

    // ---- R-D ----

    @Test
    @DisplayName("R-D: evaluation racing a budget delete waits for the lock, inserts nothing and raises no FK error")
    void evaluationDuringDelete_noOrphans() throws Exception {
        long user = newUser();
        long acc = account();
        long b = createBudget(Category.DINING, "10.00", 2026, 3);
        var deleteLocked = new CountDownLatch(1);
        var releaseDelete = new CountDownLatch(1);

        Future<Object> deleteTx = inThread(user, () -> tx.execute(s -> {
            budgetService.delete(b);        // budget row locked FOR UPDATE, alerts deleted
            deleteLocked.countDown();
            await(releaseDelete);
            return null;
        }));
        await(deleteLocked);

        Future<Long> spendTx = inThread(user, () -> spend(acc, Category.DINING, "-50.00", MARCH_10));
        Thread.sleep(1500);
        assertThat(spendTx.isDone()).as("evaluation must wait for the delete's row lock").isFalse();

        releaseDelete.countDown();
        deleteTx.get(WAIT_SECONDS, TimeUnit.SECONDS);
        long txId = spendTx.get(WAIT_SECONDS, TimeUnit.SECONDS);   // no exception reaches the caller

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE id = ?", Integer.class, b)).isZero();
        assertThat(alertsInDb(b)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE id = ?", Integer.class, txId)).isEqualTo(1);
    }

    // ---- R-E ----

    @Test
    @DisplayName("R-E: failed event evaluation keeps the transaction committed; a progress read recovers the alert")
    void failedEvaluation_recoveredByProgressRead() {
        newUser();
        long acc = account();
        long b = createBudget(Category.DINING, "100.00", 2026, 3);
        doThrow(new IllegalStateException("simulated evaluation failure"))
                .when(spyTarget()).evaluateAffectedInNewTransaction(any());

        long txId = spend(acc, Category.DINING, "-90.00", MARCH_10);  // must not throw

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE id = ?", Integer.class, txId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, acc))
                .isEqualByComparingTo("4910.00");
        assertThat(alertsInDb(b)).as("evaluation failed, alert not yet fired").isEmpty();

        var progress = budgetService.getProgress(b, "2026", "3");

        assertThat(progress.alertsFired()).containsExactly(80);
        assertThat(alertsInDb(b)).containsExactly(80);
    }

    // ---- R-F ----

    @Test
    @DisplayName("R-F: concurrent transactions crossing 80% and 100% never duplicate an alert")
    void concurrentCrossing_noDuplicates() throws Exception {
        long user = newUser();
        long acc = account();
        long b = createBudget(Category.DINING, "100.00", 2026, 3);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Long>>();
        for (int i = 0; i < 8; i++) {
            futures.add(inThread(user, () -> {
                await(start);
                return spend(acc, Category.DINING, "-15.00", MARCH_10);
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        var perThreshold = jdbc.queryForList(
                "SELECT COUNT(*) FROM budget_alerts WHERE budget_id = ? GROUP BY threshold_pct", Integer.class, b);
        assertThat(perThreshold).as("at most one row per threshold").allMatch(count -> count == 1);

        budgetService.getProgress(b, "2026", "3");   // recovery path closes any lock-failure gap
        assertThat(alertsInDb(b)).containsExactly(80, 100);
    }

    // ---- R-G ----

    @Test
    @DisplayName("R-G: repeated progress reads and repeated events keep exactly one row per threshold")
    void repeatedTriggers_idempotent() {
        newUser();
        long acc = account();
        long b = createBudget(Category.DINING, "100.00", 2026, 3);
        spend(acc, Category.DINING, "-120.00", MARCH_10);

        for (int i = 0; i < 3; i++) {
            assertThat(budgetService.getProgress(b, "2026", "3").alertsFired()).containsExactly(80, 100);
        }
        spend(acc, Category.DINING, "-1.00", MARCH_10);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_alerts WHERE budget_id = ?", Integer.class, b))
                .isEqualTo(2);
    }

    // ---- R-H ----

    @Test
    @DisplayName("R-H: moving a transaction to another category or month evaluates the new budget")
    void editAcrossCategoryAndMonth() {
        newUser();
        long acc = account();
        long dining = createBudget(Category.DINING, "100.00", 2026, 3);
        long groceries = createBudget(Category.GROCERIES, "100.00", 2026, 3);
        long aprilGroceries = createBudget(Category.GROCERIES, "50.00", 2026, 4);
        long t = spend(acc, Category.DINING, "-90.00", MARCH_10);
        assertThat(alertsInDb(dining)).containsExactly(80);

        transactionService.update(t, new UpdateTransactionRequest(new BigDecimal("-90.00"), MARCH_10, Category.GROCERIES, null));
        assertThat(alertsInDb(groceries)).containsExactly(80);
        assertThat(alertsInDb(dining)).as("fired alerts are never removed").containsExactly(80);

        transactionService.update(t, new UpdateTransactionRequest(
                new BigDecimal("-90.00"), LocalDate.of(2026, 4, 5), Category.GROCERIES, null));
        assertThat(alertsInDb(aprilGroceries)).containsExactly(80, 100);
    }
}
