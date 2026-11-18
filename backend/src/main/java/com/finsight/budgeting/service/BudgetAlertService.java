package com.finsight.budgeting.service;

import com.finsight.budgeting.domain.Budget;
import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.transactions.event.TransactionsChangedEvent.CategoryPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fires the 80% and 100% budget alerts (T-33, FR-04-3).
 *
 * <h2>Alerts are a pure function</h2>
 * The fired thresholds depend only on the budget (limit, category, own period) and the committed debits
 * in that category and period. Evaluation inserts every reached threshold with {@code INSERT IGNORE},
 * so it is idempotent: running it again, from any trigger, can never create a duplicate and always
 * repairs a missed alert.
 *
 * <h2>Ownership (AC-OWN-B4)</h2>
 * Every evaluation resolves the budget through {@link BudgetRepository#findByIdAndUserId} with the
 * security-context user before touching any alert, and the insert itself re-checks the owner.
 *
 * <h2>Transaction boundaries</h2>
 * <ul>
 *   <li>{@link #evaluate} — {@code MANDATORY}: joins the caller's transaction (used by the read-write
 *       {@code getProgress}).</li>
 *   <li>{@link #evaluateBudgetInNewTransaction}, {@link #evaluateAffectedInNewTransaction} —
 *       {@code REQUIRES_NEW}: called by {@link BudgetAlertListener} after the triggering commit. The new
 *       transaction's snapshot starts after that commit, so it sees the committed data.</li>
 * </ul>
 * Only the budget's own period is ever evaluated.
 */
@Service
public class BudgetAlertService {

    /** Thresholds in percent of the monthly limit. */
    static final List<Integer> THRESHOLDS = List.of(80, 100);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BudgetRepository budgetRepository;
    private final BudgetAlertRepository alertRepository;
    private final SpendingCalculator spendingCalculator;

    public BudgetAlertService(BudgetRepository budgetRepository,
                              BudgetAlertRepository alertRepository,
                              SpendingCalculator spendingCalculator) {
        this.budgetRepository = budgetRepository;
        this.alertRepository = alertRepository;
        this.spendingCalculator = spendingCalculator;
    }

    /** Result of one evaluation: committed spending and the fired thresholds afterwards. */
    public record Evaluation(BigDecimal spent, List<Integer> firedThresholds) {}

    /**
     * Evaluates one of the caller's budgets inside the caller's transaction.
     *
     * @param budgetId budget to evaluate
     * @return spending for the budget's own period and the fired thresholds after evaluation
     * @throws BudgetNotFoundException if missing or not owned
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Evaluation evaluate(Long budgetId) {
        return evaluateOwned(budgetId).orElseThrow(() -> new BudgetNotFoundException(budgetId));
    }

    /**
     * Evaluates one of the caller's budgets in a new transaction (after a budget create or limit change).
     * A budget deleted in the meantime is skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateBudgetInNewTransaction(Long budgetId) {
        evaluateOwned(budgetId);
    }

    /**
     * Evaluates the caller's budgets for each changed (category, month) in a new transaction
     * (after a transaction create, update, delete or category override). Pairs with no budget are skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateAffectedInNewTransaction(Set<CategoryPeriod> affected) {
        var userId = SecurityUtils.currentUserId();
        for (var pair : affected) {
            budgetRepository.findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
                            userId, pair.category(),
                            (short) pair.period().getYear(), (byte) pair.period().getMonthValue())
                    .ifPresent(budget -> evaluateOwned(budget.getId()));
        }
    }

    /**
     * Fired thresholds of one of the caller's budgets (AC-OWN-B4: budget resolved first).
     *
     * @throws BudgetNotFoundException if missing or not owned
     */
    @Transactional(readOnly = true)
    public List<Integer> firedThresholds(Long budgetId) {
        var userId = SecurityUtils.currentUserId();
        budgetRepository.findByIdAndUserId(budgetId, userId)
                .orElseThrow(() -> new BudgetNotFoundException(budgetId));
        return thresholds(budgetId, userId);
    }

    /** {@code true} when {@code spent} reaches {@code pct}% of {@code limit} (exact decimal comparison). */
    static boolean reached(BigDecimal spent, BigDecimal limit, int pct) {
        return spent.multiply(HUNDRED).compareTo(limit.multiply(BigDecimal.valueOf(pct))) >= 0;
    }

    private Optional<Evaluation> evaluateOwned(Long budgetId) {
        var userId = SecurityUtils.currentUserId();
        return budgetRepository.findByIdAndUserId(budgetId, userId).map(budget -> fire(budget, userId));
    }

    private Evaluation fire(Budget budget, Long userId) {
        var spent = spendingCalculator.debitTotal(budget.getCategory(), budget.period());
        for (int pct : THRESHOLDS) {
            if (reached(spent, budget.getMonthlyLimit(), pct)) {
                alertRepository.fireIfAbsent(budget.getId(), userId, pct);
            }
        }
        return new Evaluation(spent, thresholds(budget.getId(), userId));
    }

    private List<Integer> thresholds(Long budgetId, Long userId) {
        return alertRepository.firedThresholds(budgetId, userId).stream().map(Number::intValue).toList();
    }
}
