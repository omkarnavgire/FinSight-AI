package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.domain.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction view returned by the API. Contains no {@code userId}.
 */
public record TransactionDto(
        Long id,
        Long accountId,
        BigDecimal amount,
        Category category,
        String description,
        LocalDate transactionDate,
        String countryCode,
        boolean manual,
        LocalDateTime createdAt
) {
    /** Maps an entity to its API view. */
    public static TransactionDto from(Transaction t) {
        return new TransactionDto(
                t.getId(),
                t.getAccountId(),
                t.getAmount(),
                t.getCategory(),
                t.getDescription(),
                t.getTransactionDate(),
                t.getCountryCode(),
                t.isManual(),
                t.getCreatedAt());
    }
}
