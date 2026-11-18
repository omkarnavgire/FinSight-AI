package com.finsight.accounts;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.AccountNotFoundException;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AccountService} on real MySQL 8 (Testcontainers).
 *
 * <p>Balances are always read back with raw SQL, not through JPA, so the tests see
 * exactly what is committed. {@code ddl-auto=validate} also proves the entity matches V3.
 */
@SpringBootTest
@Testcontainers
class AccountServiceIT {

    private static final long USER_A = 101L;
    private static final long USER_B = 102L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_accounts_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret",
                () -> "accounts-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private Long openAccount(long owner, String opening) {
        actAs(owner);
        return accountService.create(
                new CreateAccountRequest("IT account", AccountType.CHECKING, new BigDecimal(opening), null)).id();
    }

    private BigDecimal dbBalance(Long id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    private BigDecimal dbOpeningBalance(Long id) {
        return jdbc.queryForObject("SELECT opening_balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    @Test
    @DisplayName("applyDelta adds signed amounts and never changes opening_balance (AC-BAL-2, AC-BAL-7)")
    void applyDelta_updatesBalanceOnly() {
        var id = openAccount(USER_A, "1000.00");

        tx.executeWithoutResult(s -> accountService.applyDelta(id, new BigDecimal("50.00")));
        tx.executeWithoutResult(s -> accountService.applyDelta(id, new BigDecimal("-80.00")));

        assertThat(dbBalance(id)).isEqualByComparingTo("970.00");
        assertThat(dbOpeningBalance(id)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("applyDelta with zero on an owned active account succeeds (driver reports matched rows)")
    void applyDelta_zero_succeeds() {
        var id = openAccount(USER_A, "10.00");

        tx.executeWithoutResult(s -> accountService.applyDelta(id, BigDecimal.ZERO));

        assertThat(dbBalance(id)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("applyDelta by another user returns 404 and leaves the owner's balance unchanged")
    void applyDelta_otherUser_notFound_balanceUnchanged() {
        var id = openAccount(USER_A, "500.00");

        actAs(USER_B);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> accountService.applyDelta(id, new BigDecimal("-500.00"))))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(dbBalance(id)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("applyDelta on a deactivated account returns 409 and leaves the balance unchanged")
    void applyDelta_inactive_conflict_balanceUnchanged() {
        var id = openAccount(USER_A, "200.00");
        accountService.deactivate(id);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> accountService.applyDelta(id, BigDecimal.ONE)))
                .isInstanceOf(AccountDeactivatedException.class);

        assertThat(dbBalance(id)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("applyDelta outside a transaction is refused (Propagation.MANDATORY)")
    void applyDelta_withoutTransaction_refused() {
        var id = openAccount(USER_A, "1.00");

        assertThatThrownBy(() -> accountService.applyDelta(id, BigDecimal.ONE))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(dbBalance(id)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("A failure later in the caller's transaction rolls back the balance change (NFR-03-2)")
    void applyDelta_rolledBackWithCallerTransaction() {
        var id = openAccount(USER_A, "300.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            accountService.applyDelta(id, new BigDecimal("-100.00"));
            throw new IllegalStateException("simulated failure after the balance update");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(dbBalance(id)).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("deactivate keeps both balances and is idempotent")
    void deactivate_keepsBalances_idempotent() {
        var id = openAccount(USER_A, "42.50");

        accountService.deactivate(id);
        accountService.deactivate(id);

        var active = jdbc.queryForObject("SELECT active FROM accounts WHERE id = ?", Boolean.class, id);
        assertThat(active).isFalse();
        assertThat(dbBalance(id)).isEqualByComparingTo("42.50");
        assertThat(dbOpeningBalance(id)).isEqualByComparingTo("42.50");
    }

    @Test
    @DisplayName("requireOwnedActive: 404 for another user, 409 when inactive")
    void requireOwnedActive_ownershipAndState() {
        var id = openAccount(USER_A, "1.00");

        actAs(USER_B);
        assertThatThrownBy(() -> accountService.requireOwnedActive(id))
                .isInstanceOf(AccountNotFoundException.class);

        actAs(USER_A);
        assertThat(accountService.requireOwnedActive(id).id()).isEqualTo(id);
        accountService.deactivate(id);
        assertThatThrownBy(() -> accountService.requireOwnedActive(id))
                .isInstanceOf(AccountDeactivatedException.class);
    }
}
