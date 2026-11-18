package com.finsight.budgeting.dto;

import com.finsight.budgeting.domain.Budget;
import com.finsight.transactions.domain.Category;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Budget view returned by the API. Contains no {@code userId}. */
public record BudgetDto(
        Long id,
        Category category,
        BigDecimal monthlyLimit,
        int periodYear,
        int periodMonth,
        LocalDateTime createdAt
) {
    /** Maps an entity to its API view. */
    public static BudgetDto from(Budget budget) {
        return new BudgetDto(
                budget.getId(),
                budget.getCategory(),
                budget.getMonthlyLimit(),
                budget.getPeriodYear(),
                budget.getPeriodMonth(),
                budget.getCreatedAt());
    }
}
