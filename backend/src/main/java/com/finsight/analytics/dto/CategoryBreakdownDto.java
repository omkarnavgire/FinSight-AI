package com.finsight.analytics.dto;

import com.finsight.transactions.domain.Category;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Spending per category in a date range (FR-05-1). Also used for the top-categories view (D-5),
 * where {@code items} holds at most five entries and {@code total} is still all spending in the
 * window (D-12).
 *
 * @param from  first day, inclusive
 * @param to    last day, inclusive
 * @param total all debits in the range, absolute value, 2 decimals; {@code 0.00} when there are none
 * @param items categories with spending, largest first; empty when there are none (D-8)
 */
public record CategoryBreakdownDto(LocalDate from, LocalDate to, BigDecimal total, List<CategoryAmount> items) {

    /**
     * One category's spending.
     *
     * @param category spending category
     * @param amount   debits in this category, absolute value, 2 decimals
     * @param percent  {@code amount * 100 / total}, 2 decimals, rounded half-up
     */
    public record CategoryAmount(Category category, BigDecimal amount, BigDecimal percent) {
    }
}
