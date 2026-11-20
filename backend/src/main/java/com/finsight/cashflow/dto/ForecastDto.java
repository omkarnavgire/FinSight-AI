package com.finsight.cashflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A cash-flow forecast for one horizon (FR-06-1, FR-06-4 to FR-06-6).
 *
 * @param horizonDays         30, 60 or 90
 * @param asOf                calculation day; {@code startingBalance} is the balance on this day
 * @param from                first forecast day ({@code asOf + 1})
 * @param to                  last forecast day ({@code asOf + horizonDays})
 * @param startingBalance     total balance of active accounts on {@code asOf}, stored with the forecast (F-8)
 * @param projectedEndBalance projected balance on {@code to} (FR-06-4)
 * @param lowConfidence       {@code true} when fewer than 30 days of history, or none
 * @param reason              {@code null}, or {@code "NO_HISTORY"} when there were no transactions
 * @param generatedAt         when the forecast was calculated
 * @param days                one point per forecast day, {@code horizonDays} entries, oldest first;
 *                            the start point is not included
 */
public record ForecastDto(int horizonDays,
                          LocalDate asOf,
                          LocalDate from,
                          LocalDate to,
                          BigDecimal startingBalance,
                          BigDecimal projectedEndBalance,
                          boolean lowConfidence,
                          String reason,
                          LocalDateTime generatedAt,
                          List<ForecastDayDto> days) {
}
