package com.finsight.transactions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Date and signed amount of one transaction. Read model for other modules (Phase 8, cash-flow);
 * returned by {@code TransactionAggregationService.amountsBetween}.
 *
 * @param date   business date of the transaction
 * @param amount signed amount: negative is a debit, positive is a credit
 */
public record DatedAmountDto(LocalDate date, BigDecimal amount) {
}
