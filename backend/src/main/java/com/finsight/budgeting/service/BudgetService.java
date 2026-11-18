package com.finsight.budgeting.service;

import com.finsight.budgeting.domain.Budget;
import com.finsight.budgeting.dto.BudgetDto;
import com.finsight.budgeting.dto.BudgetProgressDto;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.dto.UpdateBudgetRequest;
import com.finsight.budgeting.event.BudgetChangedEvent;
import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.shared.exception.BudgetAlreadyExistsException;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.shared.ownership.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

/**
 * Budget CRUD and progress (T-32, T-34).
 *
 * <p>The caller always comes from {@link SecurityUtils#currentUserId()}; another user's budget behaves
 * like a missing one ({@link BudgetNotFoundException}, 404).
 *
 * <h2>Transaction boundaries</h2>
 * <ul>
 *   <li>{@link #create}, {@link #updateLimit}: one transaction; publish {@link BudgetChangedEvent} so
 *       alerts are evaluated <em>after</em> commit (closes the budget-vs-transaction commit race).</li>
 *   <li>{@link #delete}: locks the budget row, deletes its alerts, then the budget, in one transaction.</li>
 *   <li>{@link #getProgress}: read-write on purpose — for the budget's own period it re-evaluates alerts
 *       (the recovery path, E-3) before reporting the fired thresholds.</li>
 *   <li>{@link #listCurrentPeriod}: read-only.</li>
 * </ul>
 */
@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final BudgetAlertRepository alertRepository;
    private final BudgetAlertService alertService;
    private final SpendingCalculator spendingCalculator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BudgetService(BudgetRepository budgetRepository,
                         BudgetAlertRepository alertRepository,
                         BudgetAlertService alertService,
                         SpendingCalculator spendingCalculator,
                         ApplicationEventPublisher events,
                         Clock clock) {
        this.budgetRepository = budgetRepository;
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.spendingCalculator = spendingCalculator;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates a budget for the caller (FR-04-1). The period defaults to the current one.
     *
     * @param request validated request
     * @return the created budget
     * @throws BudgetAlreadyExistsException if the caller already has one for this category and period (409)
     */
    @Transactional
    public BudgetDto create(CreateBudgetRequest request) {
        var userId = SecurityUtils.currentUserId();
        var period = PeriodParser.resolve(request.periodYear(), request.periodMonth(), clock);
        short year = (short) period.getYear();
        byte month = (byte) period.getMonthValue();

        if (budgetRepository.existsByUserIdAndCategoryAndPeriodYearAndPeriodMonth(userId, request.category(), year, month)) {
            throw new BudgetAlreadyExistsException();
        }
        Budget saved;
        try {
            saved = budgetRepository.saveAndFlush(Budget.create(userId, request.category(), request.monthlyLimit(), period));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            throw new BudgetAlreadyExistsException();
        }
        events.publishEvent(new BudgetChangedEvent(saved.getId(), userId));
        return BudgetDto.from(saved);
    }

    /**
     * Lists the caller's budgets for the current period (design: "List budgets for current period").
     *
     * @return the caller's current-period budgets, ordered by category
     */
    @Transactional(readOnly = true)
    public List<BudgetDto> listCurrentPeriod() {
        var period = YearMonth.now(clock);
        return budgetRepository.findByUserIdAndPeriodYearAndPeriodMonthOrderByCategoryAsc(
                        SecurityUtils.currentUserId(), (short) period.getYear(), (byte) period.getMonthValue())
                .stream()
                .map(BudgetDto::from)
                .toList();
    }

    /**
     * Spending versus limit for a period (FR-04-2, FR-04-5, D-2).
     *
     * <p>{@code year}/{@code month} select the spending period and default to the current period. For the
     * budget's own period this method also re-evaluates alerts (idempotent), so {@code alertsFired} is exact
     * and any missed evaluation is repaired. For other periods {@code alertsFired} is empty.
     *
     * @param budgetId budget ID from the path
     * @param year     optional year (string, strictly parsed)
     * @param month    optional month (string, strictly parsed)
     * @return the progress view
     * @throws com.finsight.shared.exception.BadRequestException for incomplete or invalid period parameters
     * @throws BudgetNotFoundException                           if missing or not owned
     */
    @Transactional
    public BudgetProgressDto getProgress(Long budgetId, String year, String month) {
        var period = PeriodParser.parse(year, month, clock);
        var budget = loadOwned(budgetId);
        if (period.equals(budget.period())) {
            var evaluation = alertService.evaluate(budgetId);
            return BudgetProgressDto.of(budget, period, evaluation.spent(), evaluation.firedThresholds());
        }
        var spent = spendingCalculator.debitTotal(budget.getCategory(), period);
        return BudgetProgressDto.of(budget, period, spent, List.of());
    }

    /**
     * Changes the limit of one of the caller's budgets (FR-04-4). Alerts are re-evaluated after commit.
     *
     * @return the updated budget
     * @throws BudgetNotFoundException if missing or not owned
     */
    @Transactional
    public BudgetDto updateLimit(Long budgetId, UpdateBudgetRequest request) {
        var budget = loadOwned(budgetId);
        budget.updateLimit(request.monthlyLimit());
        var result = BudgetDto.from(budget);
        events.publishEvent(new BudgetChangedEvent(budgetId, budget.getUserId()));
        return result;
    }

    /**
     * Deletes one of the caller's budgets and its alerts (FR-04-4).
     *
     * <p>The budget row is locked first, so a concurrent alert insert waits and then finds nothing.
     * Alerts are deleted before the budget because the foreign key has no cascade.
     *
     * @throws BudgetNotFoundException if missing or not owned
     */
    @Transactional
    public void delete(Long budgetId) {
        var userId = SecurityUtils.currentUserId();
        var budget = budgetRepository.findByIdAndUserIdForUpdate(budgetId, userId)
                .orElseThrow(() -> new BudgetNotFoundException(budgetId));
        alertRepository.deleteForBudget(budgetId, userId);
        budgetRepository.delete(budget);
    }

    private Budget loadOwned(Long budgetId) {
        return budgetRepository.findByIdAndUserId(budgetId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new BudgetNotFoundException(budgetId));
    }
}
