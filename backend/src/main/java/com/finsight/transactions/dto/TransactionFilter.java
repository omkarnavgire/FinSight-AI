package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Optional list filters (FR-03-2 and D-7). Every field may be {@code null}.
 *
 * @param accountId restrict to one of the caller's accounts; another user's ID gives 404
 * @param from      inclusive lower bound on {@code transactionDate}
 * @param to        inclusive upper bound on {@code transactionDate}
 * @param category  exact category
 * @param minAmount inclusive lower bound on the signed amount
 * @param maxAmount inclusive upper bound on the signed amount
 */
public record TransactionFilter(
        Long accountId,
        LocalDate from,
        LocalDate to,
        Category category,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
    /** A filter with no restrictions. */
    public static TransactionFilter none() {
        return new TransactionFilter(null, null, null, null, null, null);
    }
}
