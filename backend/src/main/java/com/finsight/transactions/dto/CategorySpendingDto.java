package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;

import java.math.BigDecimal;

/**
 * Total debits (absolute value) of one category in a date range. Read model for other modules
 * (Phase 7, analytics); returned by {@code TransactionService.debitTotalsByCategory}.
 *
 * @param category spending category
 * @param total    sum of {@code ABS(amount)} of the debits, always positive
 */
public record CategorySpendingDto(Category category, BigDecimal total) {
}
