package com.finsight.budgeting.dto;

import com.finsight.transactions.domain.Category;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/budgets} (FR-04-1). No {@code userId}: the owner comes from
 * the security context.
 *
 * @param category     budgeted category
 * @param monthlyLimit positive limit, at most 13 integer and 2 fraction digits
 * @param periodYear   optional; give both year and month or neither (current period, D-6)
 * @param periodMonth  optional, 1–12
 */
public record CreateBudgetRequest(

        @NotNull(message = "category is required")
        Category category,

        @NotNull(message = "monthlyLimit is required")
        @Positive(message = "monthlyLimit must be positive")
        @Digits(integer = 13, fraction = 2, message = "monthlyLimit must have at most 13 integer and 2 fraction digits")
        BigDecimal monthlyLimit,

        @Min(value = 2000, message = "periodYear must be between 2000 and 2100")
        @Max(value = 2100, message = "periodYear must be between 2000 and 2100")
        Integer periodYear,

        @Min(value = 1, message = "periodMonth must be between 1 and 12")
        @Max(value = 12, message = "periodMonth must be between 1 and 12")
        Integer periodMonth
) {}
