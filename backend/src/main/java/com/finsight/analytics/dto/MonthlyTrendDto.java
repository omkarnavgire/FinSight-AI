package com.finsight.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Total spending for the current month and the five before it (FR-05-2, D-3, D-9).
 *
 * @param months always six entries, oldest first; a month without spending has {@code 0.00}
 */
public record MonthlyTrendDto(List<MonthAmount> months) {

    /**
     * One month's spending.
     *
     * @param year  calendar year
     * @param month calendar month, 1–12
     * @param spent debits in the month, absolute value, 2 decimals
     */
    public record MonthAmount(int year, int month, BigDecimal spent) {
    }
}
