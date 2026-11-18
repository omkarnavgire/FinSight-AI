package com.finsight.budgeting;

import com.finsight.budgeting.domain.Budget;
import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.budgeting.service.BudgetAlertService;
import com.finsight.budgeting.service.SpendingCalculator;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.event.TransactionsChangedEvent.CategoryPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Threshold logic and ownership of {@link BudgetAlertService} (T-33, T-35b, AC-OWN-B4). */
@ExtendWith(MockitoExtension.class)
class BudgetAlertServiceTest {

    private static final long CALLER = 41L;
    private static final long BUDGET_ID = 7L;
    private static final YearMonth PERIOD = YearMonth.of(2026, 3);

    @Mock private BudgetRepository budgetRepository;
    @Mock private BudgetAlertRepository alertRepository;
    @Mock private SpendingCalculator spendingCalculator;

    private BudgetAlertService service;

    @BeforeEach
    void setUp() {
        service = new BudgetAlertService(budgetRepository, alertRepository, spendingCalculator);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Budget ownedBudget(String limit) {
        var b = Budget.create(CALLER, Category.DINING, new BigDecimal(limit), PERIOD);
        ReflectionTestUtils.setField(b, "id", BUDGET_ID);
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.of(b));
        return b;
    }

    private void spent(String amount) {
        when(spendingCalculator.debitTotal(Category.DINING, PERIOD)).thenReturn(new BigDecimal(amount));
    }

    @Test
    @DisplayName("79.99% fires nothing")
    void below80_noAlert() {
        ownedBudget("100.00");
        spent("79.99");

        service.evaluate(BUDGET_ID);

        verify(alertRepository, never()).fireIfAbsent(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("Exactly 80% fires 80 only")
    void exactly80_fires80() {
        ownedBudget("100.00");
        spent("80.00");

        service.evaluate(BUDGET_ID);

        verify(alertRepository).fireIfAbsent(BUDGET_ID, CALLER, 80);
        verify(alertRepository, never()).fireIfAbsent(BUDGET_ID, CALLER, 100);
    }

    @Test
    @DisplayName("Exactly 100% fires 80 and 100")
    void exactly100_firesBoth() {
        ownedBudget("100.00");
        spent("100.00");

        service.evaluate(BUDGET_ID);

        verify(alertRepository).fireIfAbsent(BUDGET_ID, CALLER, 80);
        verify(alertRepository).fireIfAbsent(BUDGET_ID, CALLER, 100);
    }

    @Test
    @DisplayName("250% fires each threshold once per evaluation (duplicates prevented by INSERT IGNORE)")
    void over100_firesEachOnce() {
        ownedBudget("40.00");
        spent("100.00");

        service.evaluate(BUDGET_ID);

        verify(alertRepository, times(1)).fireIfAbsent(BUDGET_ID, CALLER, 80);
        verify(alertRepository, times(1)).fireIfAbsent(BUDGET_ID, CALLER, 100);
    }

    @Test
    @DisplayName("evaluate returns spending and the fired thresholds read back from the database")
    void evaluate_returnsSpentAndThresholds() {
        ownedBudget("100.00");
        spent("85.00");
        when(alertRepository.firedThresholds(BUDGET_ID, CALLER)).thenReturn(List.of((short) 80));

        var result = service.evaluate(BUDGET_ID);

        assertThat(result.spent()).isEqualByComparingTo("85.00");
        assertThat(result.firedThresholds()).containsExactly(80);
    }

    @Test
    @DisplayName("Another user's budget: evaluate throws 404 and touches no alert")
    void foreignBudget_evaluate_notFound() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(BUDGET_ID)).isInstanceOf(BudgetNotFoundException.class);
        verifyNoInteractions(alertRepository, spendingCalculator);
    }

    @Test
    @DisplayName("Another user's budget: firedThresholds is inaccessible (AC-OWN-B4)")
    void foreignBudget_firedThresholds_notFound() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.firedThresholds(BUDGET_ID)).isInstanceOf(BudgetNotFoundException.class);
        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("Event path: a deleted or foreign budget is skipped silently")
    void newTransaction_missingBudget_skipped() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.empty());

        service.evaluateBudgetInNewTransaction(BUDGET_ID);

        verifyNoInteractions(alertRepository, spendingCalculator);
    }

    @Test
    @DisplayName("Event path: each changed pair is matched to the caller's budget; pairs with none are skipped")
    void affected_matchesCallerBudgets() {
        var budget = ownedBudget("10.00");
        spent("10.00");
        when(budgetRepository.findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                CALLER, Category.DINING, (short) 2026, (byte) 3)).thenReturn(Optional.of(budget));
        when(budgetRepository.findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                CALLER, Category.TRANSPORT, (short) 2026, (byte) 3)).thenReturn(Optional.empty());

        service.evaluateAffectedInNewTransaction(Set.of(
                new CategoryPeriod(Category.DINING, PERIOD), new CategoryPeriod(Category.TRANSPORT, PERIOD)));

        verify(alertRepository).fireIfAbsent(BUDGET_ID, CALLER, 100);
        verify(budgetRepository, never()).findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                eq(99L), eq(Category.DINING), anyShort(), anyByte());
    }

    @Test
    @DisplayName("Only the budget's own period is evaluated")
    void ownPeriodOnly() {
        ownedBudget("100.00");
        spent("0.00");

        service.evaluate(BUDGET_ID);

        verify(spendingCalculator).debitTotal(Category.DINING, PERIOD);
        verifyNoMoreInteractions(spendingCalculator);
    }
}
