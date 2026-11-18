package com.finsight.budgeting.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code PUT /api/v1/budgets/{id}} (FR-04-4). Only the limit can change;
 * any category or period sent by a client is ignored.
 *
 * @param monthlyLimit new positive limit
 */
public record UpdateBudgetRequest(

        @NotNull(message = "monthlyLimit is required")
        @Positive(message = "monthlyLimit must be positive")
        @Digits(integer = 13, fraction = 2, message = "monthlyLimit must have at most 13 integer and 2 fraction digits")
        BigDecimal monthlyLimit
) {}
