package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/transactions} (FR-03-3).
 *
 * <p>No {@code userId}: the owner is always resolved from the security context. Format checks
 * live here; the zero-amount rule and the ISO country allowlist are checked in the service so
 * they also protect non-HTTP callers.
 *
 * @param accountId       target account; must belong to the caller
 * @param amount          signed amount, non-zero; at most 13 integer and 2 fraction digits
 * @param transactionDate business date; future dates are allowed (D-9)
 * @param category        optional; resolved automatically when omitted (FR-03-7)
 * @param description     optional, up to 255 characters
 * @param countryCode     two uppercase letters; must be an assigned ISO 3166-1 alpha-2 code
 */
public record CreateTransactionRequest(

        @NotNull(message = "accountId is required")
        @Positive(message = "accountId must be positive")
        Long accountId,

        @NotNull(message = "amount is required")
        @Digits(integer = 13, fraction = 2, message = "amount must have at most 13 integer and 2 fraction digits")
        BigDecimal amount,

        @NotNull(message = "transactionDate is required")
        LocalDate transactionDate,

        Category category,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description,

        @NotBlank(message = "countryCode is required")
        @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be two uppercase letters")
        String countryCode
) {}
