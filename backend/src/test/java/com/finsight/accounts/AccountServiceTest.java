package com.finsight.accounts;

import com.finsight.accounts.domain.Account;
import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.repository.AccountRepository;
import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.AccountNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService} (T-23). No database; the repository is mocked.
 * The caller is always user {@value #CALLER}, set in the security context.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final long CALLER = 7L;
    private static final long OTHER_USER = 8L;

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Account account(long ownerId, String opening) {
        return Account.open(ownerId, "Main", AccountType.CHECKING, new BigDecimal(opening), "USD");
    }

    // ---- create ----

    @Test
    @DisplayName("create sets balance equal to opening balance and marks the account active")
    void create_balanceEqualsOpeningBalance() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = accountService.create(new CreateAccountRequest("Main", AccountType.SAVINGS, new BigDecimal("1000.00"), "EUR"));

        assertThat(dto.balance()).isEqualByComparingTo("1000.00");
        assertThat(dto.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(dto.active()).isTrue();
        assertThat(dto.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("create stores the owner from the security context")
    void create_ownerComesFromSecurityContext() {
        var captor = ArgumentCaptor.forClass(Account.class);
        when(accountRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        accountService.create(new CreateAccountRequest("Main", AccountType.CHECKING, BigDecimal.TEN, "USD"));

        assertThat(captor.getValue().getUserId()).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("create defaults the currency to USD when omitted")
    void create_defaultsCurrencyToUsd() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = accountService.create(new CreateAccountRequest("Main", AccountType.CHECKING, BigDecimal.ONE, null));

        assertThat(dto.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("create allows a negative opening balance")
    void create_allowsNegativeOpeningBalance() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = accountService.create(new CreateAccountRequest("Card", AccountType.CREDIT, new BigDecimal("-250.00"), "USD"));

        assertThat(dto.balance()).isEqualByComparingTo("-250.00");
    }

    // ---- read ----

    @Test
    @DisplayName("getMine on another user's account throws AccountNotFoundException")
    void getMine_otherUsersAccount_notFound() {
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getMine(5L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("getMine always queries with the caller's user ID")
    void getMine_queriesWithCallerId() {
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(account(CALLER, "10")));

        accountService.getMine(5L);

        verify(accountRepository).findByIdAndUserId(5L, CALLER);
        verify(accountRepository, never()).findByIdAndUserId(anyLong(), eq(OTHER_USER));
    }

    @Test
    @DisplayName("listMine only asks the repository for the caller's accounts")
    void listMine_scopedToCaller() {
        when(accountRepository.findByUserIdOrderByCreatedAtAscIdAsc(CALLER)).thenReturn(List.of(account(CALLER, "1")));

        assertThat(accountService.listMine()).hasSize(1);
        verify(accountRepository).findByUserIdOrderByCreatedAtAscIdAsc(CALLER);
    }

    // ---- deactivate ----

    @Test
    @DisplayName("deactivate sets active=false and leaves both balances unchanged")
    void deactivate_keepsBalances() {
        var acc = account(CALLER, "500.00");
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(acc));

        accountService.deactivate(5L);

        assertThat(acc.isActive()).isFalse();
        assertThat(acc.getBalance()).isEqualByComparingTo("500.00");
        assertThat(acc.getOpeningBalance()).isEqualByComparingTo("500.00");
        verify(accountRepository).save(acc);
    }

    @Test
    @DisplayName("deactivate is idempotent")
    void deactivate_twice_noError() {
        var acc = account(CALLER, "1");
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(acc));

        accountService.deactivate(5L);
        accountService.deactivate(5L);

        assertThat(acc.isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate on another user's account throws AccountNotFoundException and saves nothing")
    void deactivate_otherUsersAccount_notFound() {
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.deactivate(5L))
                .isInstanceOf(AccountNotFoundException.class);
        verify(accountRepository, never()).save(any());
    }

    // ---- requireOwnedActive ----

    @Test
    @DisplayName("requireOwnedActive returns the account when owned and active")
    void requireOwnedActive_ok() {
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(account(CALLER, "1")));

        assertThat(accountService.requireOwnedActive(5L).active()).isTrue();
    }

    @Test
    @DisplayName("requireOwnedActive throws AccountNotFoundException when not owned or missing")
    void requireOwnedActive_notOwned_notFound() {
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.requireOwnedActive(5L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("requireOwnedActive throws AccountDeactivatedException when owned but inactive")
    void requireOwnedActive_inactive_conflict() {
        var acc = account(CALLER, "1");
        acc.deactivate();
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> accountService.requireOwnedActive(5L))
                .isInstanceOf(AccountDeactivatedException.class);
    }

    // ---- applyDelta ----

    @Test
    @DisplayName("applyDelta passes the caller's user ID to the guarded update")
    void applyDelta_usesCallerId() {
        when(accountRepository.applyDelta(5L, CALLER, new BigDecimal("-20.00"))).thenReturn(1);

        accountService.applyDelta(5L, new BigDecimal("-20.00"));

        verify(accountRepository).applyDelta(5L, CALLER, new BigDecimal("-20.00"));
        verify(accountRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("applyDelta with no matched row on a missing or foreign account throws AccountNotFoundException")
    void applyDelta_notOwned_notFound() {
        when(accountRepository.applyDelta(5L, CALLER, BigDecimal.TEN)).thenReturn(0);
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.applyDelta(5L, BigDecimal.TEN))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("applyDelta with no matched row on an owned inactive account throws AccountDeactivatedException")
    void applyDelta_inactive_conflict() {
        var acc = account(CALLER, "1");
        acc.deactivate();
        when(accountRepository.applyDelta(5L, CALLER, BigDecimal.TEN)).thenReturn(0);
        when(accountRepository.findByIdAndUserId(5L, CALLER)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> accountService.applyDelta(5L, BigDecimal.TEN))
                .isInstanceOf(AccountDeactivatedException.class);
    }

    @Test
    @DisplayName("applyDelta rejects a null delta before touching the database")
    void applyDelta_nullDelta_rejected() {
        assertThatThrownBy(() -> accountService.applyDelta(5L, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(accountRepository);
    }
}
