package com.finsight.budgeting;

import com.finsight.budgeting.domain.Budget;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.dto.UpdateBudgetRequest;
import com.finsight.budgeting.event.BudgetChangedEvent;
import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.budgeting.service.BudgetAlertService;
import com.finsight.budgeting.service.BudgetService;
import com.finsight.budgeting.service.SpendingCalculator;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.BudgetAlreadyExistsException;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.transactions.domain.Category;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Unit tests for {@link BudgetService} (T-32, T-34, T-35b). */
@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    private static final long CALLER = 61L;
    private static final long BUDGET_ID = 3L;
    private static final Clock JULY_2026 = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock private BudgetRepository budgetRepository;
    @Mock private BudgetAlertRepository alertRepository;
    @Mock private BudgetAlertService alertService;
    @Mock private SpendingCalculator spendingCalculator;
    @Mock private ApplicationEventPublisher events;

    private BudgetService service;

    @BeforeEach
    void setUp() {
        service = new BudgetService(budgetRepository, alertRepository, alertService, spendingCalculator, events, JULY_2026);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Budget budget(String limit, YearMonth period) {
        var b = Budget.create(CALLER, Category.DINING, new BigDecimal(limit), period);
        ReflectionTestUtils.setField(b, "id", BUDGET_ID);
        return b;
    }

    private void saveReturnsWithId() {
        when(budgetRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Budget b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", BUDGET_ID);
            return b;
        });
    }

    // ---- create ----

    @Test
    @DisplayName("create stores the context user, defaults to the current period and publishes BudgetChangedEvent")
    void create_defaults_andPublishes() {
        saveReturnsWithId();
        var captor = ArgumentCaptor.forClass(Budget.class);

        var dto = service.create(new CreateBudgetRequest(Category.DINING, new BigDecimal("400.00"), null, null));

        verify(budgetRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(CALLER);
        assertThat(dto.periodYear()).isEqualTo(2026);
        assertThat(dto.periodMonth()).isEqualTo(7);
        verify(events).publishEvent(new BudgetChangedEvent(BUDGET_ID, CALLER));
    }

    @Test
    @DisplayName("create with an explicit period uses it")
    void create_explicitPeriod() {
        saveReturnsWithId();

        var dto = service.create(new CreateBudgetRequest(Category.DINING, BigDecimal.TEN, 2025, 3));

        assertThat(dto.periodYear()).isEqualTo(2025);
        assertThat(dto.periodMonth()).isEqualTo(3);
    }

    @Test
    @DisplayName("create with only one of year/month is a 400 and saves nothing")
    void create_partialPeriod_badRequest() {
        assertThatThrownBy(() -> service.create(new CreateBudgetRequest(Category.DINING, BigDecimal.TEN, 2025, null)))
                .isInstanceOf(BadRequestException.class);
        verify(budgetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create for an existing category and period is a 409")
    void create_duplicate_conflict() {
        when(budgetRepository.existsByUserIdAndCategoryAndPeriodYearAndPeriodMonth(CALLER, Category.DINING, (short) 2026, (byte) 7))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateBudgetRequest(Category.DINING, BigDecimal.TEN, null, null)))
                .isInstanceOf(BudgetAlreadyExistsException.class);
        verify(budgetRepository, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("create losing a race on the unique key is a 409 and publishes nothing")
    void create_uniqueKeyRace_conflict() {
        when(budgetRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> service.create(new CreateBudgetRequest(Category.DINING, BigDecimal.TEN, null, null)))
                .isInstanceOf(BudgetAlreadyExistsException.class);
        verifyNoInteractions(events);
    }

    // ---- ownership (AC-OWN-B1, AC-OWN-B2) ----

    @Test
    @DisplayName("Another user's budget: progress, update and delete all throw BudgetNotFoundException")
    void foreignBudget_notFoundEverywhere() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.empty());
        when(budgetRepository.findByIdAndUserIdForUpdate(BUDGET_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProgress(BUDGET_ID, null, null)).isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> service.updateLimit(BUDGET_ID, new UpdateBudgetRequest(BigDecimal.ONE)))
                .isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> service.delete(BUDGET_ID)).isInstanceOf(BudgetNotFoundException.class);

        verifyNoInteractions(alertRepository, alertService, events);
        verify(budgetRepository, never()).delete(any());
    }

    // ---- progress ----

    @Test
    @DisplayName("Progress for the budget's own period evaluates alerts and reports them")
    void progress_ownPeriod_evaluates() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.of(budget("200.00", YearMonth.of(2026, 7))));
        when(alertService.evaluate(BUDGET_ID)).thenReturn(new BudgetAlertService.Evaluation(new BigDecimal("170.00"), List.of(80)));

        var p = service.getProgress(BUDGET_ID, null, null);

        assertThat(p.spent()).isEqualByComparingTo("170.00");
        assertThat(p.remaining()).isEqualByComparingTo("30.00");
        assertThat(p.percentUsed()).isEqualByComparingTo("85.00");
        assertThat(p.alertsFired()).containsExactly(80);
        verifyNoInteractions(spendingCalculator);
    }

    @Test
    @DisplayName("Progress for another period uses that period's spending and reports no alerts")
    void progress_otherPeriod_noAlerts() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.of(budget("300.00", YearMonth.of(2026, 7))));
        when(spendingCalculator.debitTotal(Category.DINING, YearMonth.of(2025, 3))).thenReturn(new BigDecimal("100.00"));

        var p = service.getProgress(BUDGET_ID, "2025", "3");

        assertThat(p.year()).isEqualTo(2025);
        assertThat(p.month()).isEqualTo(3);
        assertThat(p.percentUsed()).isEqualByComparingTo("33.33");
        assertThat(p.alertsFired()).isEmpty();
        verify(alertService, never()).evaluate(anyLong());
    }

    @Test
    @DisplayName("Progress over the limit has negative remaining")
    void progress_overLimit_negativeRemaining() {
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.of(budget("50.00", YearMonth.of(2026, 7))));
        when(alertService.evaluate(BUDGET_ID)).thenReturn(new BudgetAlertService.Evaluation(new BigDecimal("75.00"), List.of(80, 100)));

        var p = service.getProgress(BUDGET_ID, "2026", "7");

        assertThat(p.remaining()).isEqualByComparingTo("-25.00");
        assertThat(p.percentUsed()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Progress with invalid period parameters is a 400 before any lookup")
    void progress_invalidPeriod_badRequest() {
        assertThatThrownBy(() -> service.getProgress(BUDGET_ID, "2025", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getProgress(BUDGET_ID, "abc", "3")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(budgetRepository);
    }

    // ---- update / delete / list ----

    @Test
    @DisplayName("updateLimit changes only the limit and publishes BudgetChangedEvent")
    void updateLimit_publishes() {
        var b = budget("100.00", YearMonth.of(2026, 7));
        when(budgetRepository.findByIdAndUserId(BUDGET_ID, CALLER)).thenReturn(Optional.of(b));

        var dto = service.updateLimit(BUDGET_ID, new UpdateBudgetRequest(new BigDecimal("250.00")));

        assertThat(dto.monthlyLimit()).isEqualByComparingTo("250.00");
        assertThat(dto.category()).isEqualTo(Category.DINING);
        assertThat(dto.periodMonth()).isEqualTo(7);
        verify(events).publishEvent(new BudgetChangedEvent(BUDGET_ID, CALLER));
    }

    @Test
    @DisplayName("delete locks the budget, removes its alerts, then the budget")
    void delete_order() {
        var b = budget("100.00", YearMonth.of(2026, 7));
        when(budgetRepository.findByIdAndUserIdForUpdate(BUDGET_ID, CALLER)).thenReturn(Optional.of(b));

        service.delete(BUDGET_ID);

        InOrder order = inOrder(budgetRepository, alertRepository);
        order.verify(budgetRepository).findByIdAndUserIdForUpdate(BUDGET_ID, CALLER);
        order.verify(alertRepository).deleteForBudget(BUDGET_ID, CALLER);
        order.verify(budgetRepository).delete(b);
    }

    @Test
    @DisplayName("list returns the caller's budgets for the current period")
    void list_currentPeriod() {
        when(budgetRepository.findByUserIdAndPeriodYearAndPeriodMonthOrderByCategoryAsc(CALLER, (short) 2026, (byte) 7))
                .thenReturn(List.of(budget("1.00", YearMonth.of(2026, 7))));

        assertThat(service.listCurrentPeriod()).hasSize(1);
    }
}
