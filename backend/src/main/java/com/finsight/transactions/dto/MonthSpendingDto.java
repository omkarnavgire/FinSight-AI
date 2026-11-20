package com.finsight.transactions.dto;

import java.math.BigDecimal;

/**
 * Total debits (absolute value) of one calendar month. Read model for other modules
 * (Phase 7, analytics); returned by {@code TransactionService.debitTotalsByMonth}.
 *
 * @param year  calendar year
 * @param month calendar month, 1–12
 * @param total sum of {@code ABS(amount)} of the debits, always positive
 */
public record MonthSpendingDto(int year, int month, BigDecimal total) {
}
