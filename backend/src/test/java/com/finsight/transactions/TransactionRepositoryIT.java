package com.finsight.transactions;

import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TransactionRepository} native queries on MySQL 8: ownership scoping, filters, order, paging.
 * Data is inserted with raw SQL so every test controls exactly which rows exist.
 * {@code ddl-auto=validate} also proves the entity matches V4 (including {@code CHAR(2)}).
 */
@SpringBootTest
@Testcontainers
class TransactionRepositoryIT {

    private static final long USER_A = 401L;
    private static final long USER_B = 402L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_tx_repo_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "tx-repo-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private TransactionRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private long accountA1;
    private long accountA2;
    private long accountB;
    private long txA1Old;
    private long txA1New;
    private long txA2;
    private long txB;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM accounts");
        accountA1 = insertAccount(USER_A);
        accountA2 = insertAccount(USER_A);
        accountB = insertAccount(USER_B);
        txA1Old = insertTx(accountA1, "-20.00", "DINING", LocalDate.of(2026, 1, 1));
        txA1New = insertTx(accountA1, "-150.00", "GROCERIES", LocalDate.of(2026, 3, 1));
        txA2 = insertTx(accountA2, "2000.00", "INCOME", LocalDate.of(2026, 2, 1));
        txB = insertTx(accountB, "-5.00", "DINING", LocalDate.of(2026, 2, 15));
    }

    private long insertAccount(long userId) {
        return insertReturningId("INSERT INTO accounts (user_id, name, type, opening_balance, balance, currency, active) "
                + "VALUES (?, 'Seed', 'CHECKING', 0, 0, 'USD', TRUE)", userId);
    }

    private long insertTx(long accountId, String amount, String category, LocalDate date) {
        return insertReturningId("INSERT INTO transactions (account_id, amount, category, description, transaction_date, country_code, manual) "
                + "VALUES (?, ?, ?, 'seed', ?, 'US', TRUE)", accountId, new BigDecimal(amount), category, date);
    }

    /** Inserts with bound parameters and returns the generated key from the same statement. */
    private long insertReturningId(String sql, Object... args) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private java.util.List<Long> ids(org.springframework.data.domain.Page<Transaction> page) {
        return page.getContent().stream().map(Transaction::getId).toList();
    }

    @Test
    @DisplayName("findByIdAndUserId returns the owner's row and empty for another user")
    void findByIdAndUserId_isOwnerScoped() {
        assertThat(repository.findByIdAndUserId(txA1Old, USER_A)).isPresent();
        assertThat(repository.findByIdAndUserId(txA1Old, USER_B)).isEmpty();
        assertThat(repository.findByIdAndUserId(999_999L, USER_A)).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("findByIdAndUserIdForUpdate is owner-scoped and runs inside a transaction")
    void findForUpdate_isOwnerScoped() {
        assertThat(repository.findByIdAndUserIdForUpdate(txB, USER_B)).isPresent();
        assertThat(repository.findByIdAndUserIdForUpdate(txB, USER_A)).isEmpty();
    }

    @Test
    @DisplayName("Unfiltered search returns only the user's rows, newest transaction_date first")
    void search_unfiltered_ownerOnly_ordered() {
        var page = repository.search(USER_A, null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactly(txA1New, txA2, txA1Old);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("No filter combination returns another user's rows")
    void search_neverLeaksOtherUsersRows() {
        var byForeignAccount = repository.search(USER_A, accountB, null, null, null, null, null, PageRequest.of(0, 20));
        var byCategory = repository.search(USER_A, null, null, null, "DINING", null, null, PageRequest.of(0, 20));

        assertThat(byForeignAccount.getContent()).isEmpty();
        assertThat(ids(byCategory)).containsExactly(txA1Old);
    }

    @Test
    @DisplayName("Each filter narrows results: account, date range, category, amount range")
    void search_filters() {
        assertThat(ids(repository.search(USER_A, accountA2, null, null, null, null, null, PageRequest.of(0, 20))))
                .containsExactly(txA2);
        assertThat(ids(repository.search(USER_A, null, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 28),
                null, null, null, PageRequest.of(0, 20))))
                .containsExactly(txA2);
        assertThat(ids(repository.search(USER_A, null, null, null, "GROCERIES", null, null, PageRequest.of(0, 20))))
                .containsExactly(txA1New);
        assertThat(ids(repository.search(USER_A, null, null, null, null,
                new BigDecimal("-100.00"), new BigDecimal("0.00"), PageRequest.of(0, 20))))
                .containsExactly(txA1Old);
    }

    @Test
    @DisplayName("Paging is stable: ties on transaction_date are ordered by id descending")
    void search_paging_stableOrder() {
        var sameDay = LocalDate.of(2026, 4, 1);
        long first = insertTx(accountA1, "-1.00", "OTHER", sameDay);
        long second = insertTx(accountA1, "-2.00", "OTHER", sameDay);

        var page0 = repository.search(USER_A, null, null, null, null, null, null, PageRequest.of(0, 2));
        var page1 = repository.search(USER_A, null, null, null, null, null, null, PageRequest.of(1, 2));

        assertThat(ids(page0)).containsExactly(second, first);
        assertThat(ids(page1)).containsExactly(txA1New, txA2);
        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getTotalPages()).isEqualTo(3);
    }
}
