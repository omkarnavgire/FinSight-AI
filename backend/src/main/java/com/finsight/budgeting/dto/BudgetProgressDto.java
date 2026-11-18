package com.finsight.budgeting.dto;

import com.finsight.budgeting.domain.Budget;
import com.finsight.transactions.domain.Category;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

/**
 * Spending versus limit for one period (FR-04-2, FR-04-5).
 *
 * @param budgetId     budget the progress belongs to
 * @param category     budgeted category
 * @param year         period year
 * @param month        period month (1–12)
 * @param monthlyLimit the budget's limit
 * @param spent        total debits in the category and period (non-negative)
 * @param remaining    {@code monthlyLimit - spent}; negative when over budget
 * @param percentUsed  {@code spent / monthlyLimit × 100}, 2 decimals, HALF_UP
 * @param alertsFired  fired thresholds (80, 100); only reported for the budget's own period (D-2)
 */
public record BudgetProgressDto(
        Long budgetId,
        Category category,
        int year,
        int month,
        BigDecimal monthlyLimit,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        List<Integer> alertsFired
) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Builds the progress view. {@code monthlyLimit} is always positive (validated on input). */
    public static BudgetProgressDto of(Budget budget, YearMonth period, BigDecimal spent, List<Integer> alertsFired) {
        var limit = budget.getMonthlyLimit();
        var percent = spent.multiply(HUNDRED).divide(limit, 2, RoundingMode.HALF_UP);
        return new BudgetProgressDto(
                budget.getId(),
                budget.getCategory(),
                period.getYear(),
                period.getMonthValue(),
                limit,
                spent,
                limit.subtract(spent),
                percent,
                List.copyOf(alertsFired));
    }
}
