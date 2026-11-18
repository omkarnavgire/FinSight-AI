package com.finsight.transactions;

import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.InvalidCountryCodeException;
import com.finsight.shared.exception.TransactionNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.UpdateTransactionRequest;
import com.finsight.transactions.event.TransactionsChangedEvent;
import com.finsight.transactions.event.TransactionsChangedEvent.CategoryPeriod;
import com.finsight.transactions.repository.TransactionRepository;
import com.finsight.transactions.service.CategoryResolver;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Phase 6 additive change to {@link TransactionService}: {@link TransactionsChangedEvent} publishing at
 * create, update, delete and category override. Also checks the balance update is unchanged and
 * happens before publishing.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceEventTest {

    private static final long CALLER = 21L;
    private static final long ACCOUNT = 6L;
    private static final long TX_ID = 31L;
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 2);

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountService accountService;
    @Mock private CategoryResolver categoryResolver;
    @Mock private ApplicationEventPublisher events;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(transactionRepository, accountService, categoryResolver, events);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private TransactionsChangedEvent published() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        return (TransactionsChangedEvent) captor.getValue();
    }

    private static Transaction existing(Category category, LocalDate date) {
        return Transaction.manual(ACCOUNT, new BigDecimal("-50.00"), category, "x", date, "US");
    }

    private static CategoryPeriod pair(Category c, int year, int month) {
        return new CategoryPeriod(c, YearMonth.of(year, month));
    }

    @Test
    @DisplayName("create publishes the new (category, month) for the caller after the balance update")
    void create_publishesNewPair_afterApplyDelta() {
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateTransactionRequest(ACCOUNT, new BigDecimal("-12.00"), MARCH, Category.DINING, "x", "US"));

        InOrder order = inOrder(accountService, events);
        order.verify(accountService).applyDelta(eq(ACCOUNT), argThat(d -> d.compareTo(new BigDecimal("-12.00")) == 0));
        order.verify(events).publishEvent(any(Object.class));
        var event = published();
        assertThat(event.userId()).isEqualTo(CALLER);
        assertThat(event.affected()).containsExactly(pair(Category.DINING, 2026, 3));
    }

    @Test
    @DisplayName("update keeping category and month publishes one pair")
    void update_samePair_publishesOne() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.DINING, MARCH)));

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-80.00"), MARCH.plusDays(3), Category.DINING, null));

        assertThat(published().affected()).containsExactly(pair(Category.DINING, 2026, 3));
    }

    @Test
    @DisplayName("update changing the category publishes old and new pairs")
    void update_categoryChange_publishesBoth() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.DINING, MARCH)));

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-50.00"), MARCH, Category.GROCERIES, null));

        assertThat(published().affected())
                .containsExactlyInAnyOrder(pair(Category.DINING, 2026, 3), pair(Category.GROCERIES, 2026, 3));
    }

    @Test
    @DisplayName("update changing the month publishes old and new pairs")
    void update_monthChange_publishesBoth() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.DINING, MARCH)));

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-50.00"), APRIL, Category.DINING, null));

        assertThat(published().affected())
                .containsExactlyInAnyOrder(pair(Category.DINING, 2026, 3), pair(Category.DINING, 2026, 4));
    }

    @Test
    @DisplayName("update still applies the net delta exactly once")
    void update_balanceUnchanged() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.DINING, MARCH)));

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-80.00"), MARCH, Category.DINING, null));

        verify(accountService, times(1)).applyDelta(eq(ACCOUNT), argThat(d -> d.compareTo(new BigDecimal("-30.00")) == 0));
    }

    @Test
    @DisplayName("delete publishes the removed transaction's pair")
    void delete_publishesOldPair() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.TRANSPORT, APRIL)));

        service.delete(TX_ID);

        assertThat(published().affected()).containsExactly(pair(Category.TRANSPORT, 2026, 4));
    }

    @Test
    @DisplayName("category override publishes old and new categories for the month, with no balance change")
    void override_publishesBoth_noBalanceChange() {
        when(transactionRepository.findByIdAndUserId(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.OTHER, MARCH)));

        service.overrideCategory(TX_ID, Category.DINING);

        assertThat(published().affected())
                .containsExactlyInAnyOrder(pair(Category.OTHER, 2026, 3), pair(Category.DINING, 2026, 3));
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("category override to the same category publishes one pair")
    void override_sameCategory_publishesOne() {
        when(transactionRepository.findByIdAndUserId(TX_ID, CALLER))
                .thenReturn(Optional.of(existing(Category.DINING, MARCH)));

        service.overrideCategory(TX_ID, Category.DINING);

        assertThat(published().affected()).containsExactly(pair(Category.DINING, 2026, 3));
    }

    @Test
    @DisplayName("Nothing is published when validation, ownership or state checks fail")
    void failures_publishNothing() {
        assertThatThrownBy(() -> service.create(new CreateTransactionRequest(
                ACCOUNT, new BigDecimal("-1.00"), MARCH, Category.DINING, null, "ZZ")))
                .isInstanceOf(InvalidCountryCodeException.class);

        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(TX_ID)).isInstanceOf(TransactionNotFoundException.class);

        when(accountService.requireOwnedActive(ACCOUNT)).thenThrow(new AccountDeactivatedException(ACCOUNT));
        assertThatThrownBy(() -> service.create(new CreateTransactionRequest(
                ACCOUNT, new BigDecimal("-1.00"), MARCH, Category.DINING, null, "US")))
                .isInstanceOf(AccountDeactivatedException.class);

        verifyNoInteractions(events);
    }
}
