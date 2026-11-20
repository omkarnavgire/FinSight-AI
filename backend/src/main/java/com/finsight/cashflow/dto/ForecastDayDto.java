package com.finsight.cashflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Projected balance at the end of one forecast day.
 *
 * @param date             forecast day
 * @param projectedBalance projected total balance of the user's active accounts, 2 decimals
 */
public record ForecastDayDto(LocalDate date, BigDecimal projectedBalance) {
}
