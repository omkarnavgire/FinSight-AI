package com.finsight.analytics.service;

import com.finsight.analytics.dto.CategoryBreakdownDto;
import com.finsight.analytics.dto.CategoryBreakdownDto.CategoryAmount;
import com.finsight.analytics.dto.MonthlyTrendDto;
import com.finsight.analytics.dto.MonthlyTrendDto.MonthAmount;
import com.finsight.transactions.dto.CategorySpendingDto;
import com.finsight.transactions.service.TransactionAggregationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Spending analytics (T-36): category breakdown, six-month trend and top categories.
 *
 * <h2>Data source</h2>
 * All totals come from {@link TransactionAggregationService}, which groups in the database and
 * scopes to the signed-in user. This class never sees or accepts a user ID. "Spending" means
 * debits only, as absolute values (D-2).
 *
 * <h2>Money</h2>
 * Amounts and totals have 2 decimals. Percentages are {@code amount * 100 / total}, 2 decimals,
 * rounded half-up. With no spending the total is {@code 0.00}, the list is empty, and no
 * division happens.
 */
@Service
public class AnalyticsService {

    static final int TREND_MONTHS = 6;
    static final int TOP_LIMIT = 5;
    static final int WINDOW_DAYS = 30;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final TransactionAggregationService aggregationService;
    private final Clock clock;

    public AnalyticsService(TransactionAggregationService aggregationService, Clock clock) {
        this.aggregationService = aggregationService;
        this.clock = clock;
    }

    /**
     * Spending per category for one calendar month (FR-05-1).
     *
     * @param year  optional year; must be given together with {@code month}
     * @param month optional month 1–12; both absent means the current month
     * @return breakdown for the 1st to the last day of the month
     */
    public CategoryBreakdownDto categoryBreakdown(String year, String month) {
        var period = AnalyticsParams.month(year, month, clock);
        var from = period.atDay(1);
        var to = period.atEndOfMonth();
        var rows = aggregationService.debitTotalsByCategory(from, to);
        return breakdown(from, to, rows, sum(rows), rows.size());
    }

    /**
     * Total spending for the current month and the five before it, oldest first (FR-05-2, D-3).
     * Months without spending are filled with {@code 0.00}, so there are always six entries.
     *
     * @return six-month trend
     */
    public MonthlyTrendDto monthlyTrend() {
        var current = YearMonth.now(clock);
        var start = current.minusMonths(TREND_MONTHS - 1L);
        var totals = new HashMap<YearMonth, BigDecimal>();
        for (var row : aggregationService.debitTotalsByMonth(start.atDay(1), current.atEndOfMonth())) {
            totals.put(YearMonth.of(row.year(), row.month()), row.total());
        }
        var months = new ArrayList<MonthAmount>(TREND_MONTHS);
        for (var m = start; !m.isAfter(current); m = m.plusMonths(1)) {
            months.add(new MonthAmount(m.getYear(), m.getMonthValue(), money(totals.getOrDefault(m, ZERO))));
        }
        return new MonthlyTrendDto(List.copyOf(months));
    }

    /**
     * The five categories with the most spending in the 30 days ending on {@code end} (FR-05-3).
     * {@code total} and every percentage are relative to all spending in the window (D-12),
     * not only the five shown.
     *
     * @param end optional last day {@code YYYY-MM-DD}, inclusive; absent means today (D-4)
     * @return top categories for {@code end - 29 … end}
     */
    public CategoryBreakdownDto topCategories(String end) {
        var to = AnalyticsParams.endDate(end, clock);
        var from = to.minusDays(WINDOW_DAYS - 1L);
        var rows = aggregationService.debitTotalsByCategory(from, to);
        return breakdown(from, to, rows, sum(rows), TOP_LIMIT);
    }

    // ---- helpers ----

    /** Builds the DTO from rows already sorted by total descending, keeping the first {@code limit}. */
    private static CategoryBreakdownDto breakdown(LocalDate from, LocalDate to, List<CategorySpendingDto> rows,
                                                  BigDecimal total, int limit) {
        var items = rows.stream()
                .limit(limit)
                .map(r -> new CategoryAmount(r.category(), money(r.total()), percent(r.total(), total)))
                .toList();
        return new CategoryBreakdownDto(from, to, total, items);
    }

    private static BigDecimal sum(List<CategorySpendingDto> rows) {
        return money(rows.stream().map(CategorySpendingDto::total).reduce(ZERO, BigDecimal::add));
    }

    /** Only called for a row that exists, so {@code total > 0}; the guard keeps division impossible anyway. */
    private static BigDecimal percent(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return ZERO;
        }
        return amount.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
