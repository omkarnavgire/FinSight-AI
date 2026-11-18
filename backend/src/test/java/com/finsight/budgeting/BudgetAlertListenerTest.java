package com.finsight.budgeting;

import com.finsight.budgeting.event.BudgetChangedEvent;
import com.finsight.budgeting.service.BudgetAlertListener;
import com.finsight.budgeting.service.BudgetAlertService;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.event.TransactionsChangedEvent;
import com.finsight.transactions.event.TransactionsChangedEvent.CategoryPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.UnexpectedRollbackException;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** {@link BudgetAlertListener}: failure isolation, single retry on lock conflicts, user guard. */
@ExtendWith(MockitoExtension.class)
class BudgetAlertListenerTest {

    private static final long CALLER = 51L;

    @Mock private BudgetAlertService alertService;

    private BudgetAlertListener listener;

    private final TransactionsChangedEvent txEvent = new TransactionsChangedEvent(
            CALLER, Set.of(new CategoryPeriod(Category.DINING, YearMonth.of(2026, 3))));

    @BeforeEach
    void setUp() {
        listener = new BudgetAlertListener(alertService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Transaction event delegates to the REQUIRES_NEW evaluation")
    void transactionEvent_delegates() {
        listener.onTransactionsChanged(txEvent);

        verify(alertService).evaluateAffectedInNewTransaction(txEvent.affected());
    }

    @Test
    @DisplayName("Budget event delegates to the REQUIRES_NEW evaluation")
    void budgetEvent_delegates() {
        listener.onBudgetChanged(new BudgetChangedEvent(9L, CALLER));

        verify(alertService).evaluateBudgetInNewTransaction(9L);
    }

    @Test
    @DisplayName("Any evaluation failure is caught; nothing reaches the committed caller")
    void failure_isSwallowed() {
        doThrow(new IllegalStateException("boom")).when(alertService).evaluateAffectedInNewTransaction(any());

        assertThatCode(() -> listener.onTransactionsChanged(txEvent)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A failed commit of the new transaction (UnexpectedRollbackException) is also caught")
    void failedCommit_isSwallowed() {
        doThrow(new UnexpectedRollbackException("rollback-only")).when(alertService).evaluateBudgetInNewTransaction(9L);

        assertThatCode(() -> listener.onBudgetChanged(new BudgetChangedEvent(9L, CALLER))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A lock conflict is retried exactly once")
    void lockConflict_retriedOnce() {
        doThrow(new CannotAcquireLockException("deadlock")).doNothing()
                .when(alertService).evaluateAffectedInNewTransaction(any());

        listener.onTransactionsChanged(txEvent);

        verify(alertService, times(2)).evaluateAffectedInNewTransaction(any());
    }

    @Test
    @DisplayName("A second lock conflict is not retried again and is swallowed")
    void lockConflictTwice_swallowed() {
        doThrow(new CannotAcquireLockException("deadlock")).when(alertService).evaluateAffectedInNewTransaction(any());

        assertThatCode(() -> listener.onTransactionsChanged(txEvent)).doesNotThrowAnyException();
        verify(alertService, times(2)).evaluateAffectedInNewTransaction(any());
    }

    @Test
    @DisplayName("Event user different from the security-context user is skipped")
    void userMismatch_skipped() {
        listener.onTransactionsChanged(new TransactionsChangedEvent(999L, txEvent.affected()));

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("No security context: skipped without throwing")
    void noContext_skipped() {
        SecurityContextHolder.clearContext();

        assertThatCode(() -> listener.onTransactionsChanged(txEvent)).doesNotThrowAnyException();
        verifyNoInteractions(alertService);
    }
}
