package com.finsight.accounts.dto;

import com.finsight.accounts.domain.Account;
import com.finsight.accounts.domain.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Account view returned by the API and by {@code AccountService} to other modules.
 *
 * <p>Contains no {@code userId}: the caller already knows it is their own account.
 */
public record AccountDto(
        Long id,
        String name,
        AccountType type,
        BigDecimal openingBalance,
        BigDecimal balance,
        String currency,
        boolean active,
        LocalDateTime createdAt
) {
    /** Maps an entity to its API view. */
    public static AccountDto from(Account account) {
        return new AccountDto(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getOpeningBalance(),
                account.getBalance(),
                account.getCurrency(),
                account.isActive(),
                account.getCreatedAt());
    }
}
