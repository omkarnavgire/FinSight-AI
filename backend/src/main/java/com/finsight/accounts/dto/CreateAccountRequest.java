package com.finsight.accounts.dto;

import com.finsight.accounts.domain.AccountType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/accounts}.
 *
 * <p>There is deliberately no {@code userId} field: the owner always comes from the
 * security context (AC-OWN-3). A {@code userId} sent by a client is ignored.
 *
 * @param name           display name, 1–100 characters
 * @param type           CHECKING, SAVINGS or CREDIT
 * @param openingBalance starting balance; may be negative (G-3); at most 13 integer and 2 fraction digits
 * @param currency       three uppercase letters; optional, defaults to {@code USD} (G-1)
 */
public record CreateAccountRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @NotNull(message = "Type is required")
        AccountType type,

        @NotNull(message = "Opening balance is required")
        @Digits(integer = 13, fraction = 2, message = "Opening balance must have at most 13 integer and 2 fraction digits")
        BigDecimal openingBalance,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be three uppercase letters")
        String currency
) {}
