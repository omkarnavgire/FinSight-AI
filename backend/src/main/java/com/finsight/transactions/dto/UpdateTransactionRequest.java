package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code PUT /api/v1/transactions/{id}} (FR-03-4).
 *
 * <p>Only the four fields FR-03-4 allows are present. {@code accountId}, {@code countryCode}
 * and {@code manual} cannot be changed; any such field sent by a client is ignored.
 *
 * @param amount          new signed amount, non-zero
 * @param transactionDate new business date
 * @param category        new category
 * @param description     new description, up to 255 characters
 */
public record UpdateTransactionRequest(

        @NotNull(message = "amount is required")
        @Digits(integer = 13, fraction = 2, message = "amount must have at most 13 integer and 2 fraction digits")
        BigDecimal amount,

        @NotNull(message = "transactionDate is required")
        LocalDate transactionDate,

        @NotNull(message = "category is required")
        Category category,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description
) {}
