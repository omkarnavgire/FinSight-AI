package com.finsight.analytics;

import com.finsight.analytics.dto.CategoryBreakdownDto.CategoryAmount;
import com.finsight.analytics.dto.MonthlyTrendDto.MonthAmount;
import com.finsight.analytics.service.AnalyticsService;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CategorySpendingDto;
import com.finsight.transactions.dto.MonthSpendingDto;
import com.finsight.transactions.service.TransactionAggregationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Unit tests for {@link AnalyticsService} (T-38). {@link TransactionAggregationService} is mocked; the clock is fixed. */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private TransactionAggregationService aggregationService;

    private AnalyticsService at(String instant) {
        return new AnalyticsService(aggregationService, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static CategorySpendingDto row(Category category, String total) {
        return new CategorySpendingDto(category, new BigDecimal(total));
    }

    private static CategoryAmount item(Category category, String amount, String percent) {
        return new CategoryAmount(category, new BigDecimal(amount), new BigDecimal(percent));
    }

    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MAR_31 = LocalDate.of(2026, 3, 31);

    // ---- category breakdown ----

    @Test
    @DisplayName("Breakdown without a period covers the current calendar month from the clock")
    void breakdown_defaultsToCurrentMonth() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByCategory(MAR_1, MAR_31)).thenReturn(List.of());

        var result = service.categoryBreakdown(null, null);

        assertThat(List.of(result.from(), result.to())).containsExactly(MAR_1, MAR_31);
    }

    @Test
    @DisplayName("Breakdown for February 2024 ends on the 29th")
    void breakdown_leapFebruary() {
        var service = at("2026-03-15T10:00:00Z");
        var from = LocalDate.of(2024, 2, 1);
        var to = LocalDate.of(2024, 2, 29);
        when(aggregationService.debitTotalsByCategory(from, to)).thenReturn(List.of());

        assertThat(service.categoryBreakdown("2024", "2").to()).isEqualTo(to);
    }

    @Test
    @DisplayName("Breakdown total is the sum of all categories and each percent is amount*100/total, 2 dp, half-up")
    void breakdown_totalAndPercents() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByCategory(MAR_1, MAR_31)).thenReturn(List.of(
                row(Category.DINING, "50.00"), row(Category.GROCERIES, "25.00"), row(Category.TRANSPORT, "25.00")));

        var result = service.categoryBreakdown("2026", "3");

        assertThat(result.total()).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.items()).containsExactly(
                item(Category.DINING, "50.00", "50.00"),
                item(Category.GROCERIES, "25.00", "25.00"),
                item(Category.TRANSPORT, "25.00", "25.00"));
    }

    @Test
    @DisplayName("Percent rounding is half-up to 2 decimals (1 of 3 = 33.33, 2 of 3 = 66.67)")
    void breakdown_rounding() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByCategory(MAR_1, MAR_31)).thenReturn(List.of(
                row(Category.DINING, "2.00"), row(Category.OTHER, "1.00")));

        var result = service.categoryBreakdown(null, null);

        assertThat(result.items()).extracting(CategoryAmount::percent)
                .containsExactly(new BigDecimal("66.67"), new BigDecimal("33.33"));
    }

    @Test
    @DisplayName("Breakdown with no spending returns total 0.00 and an empty list")
    void breakdown_empty() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByCategory(MAR_1, MAR_31)).thenReturn(List.of());

        var result = service.categoryBreakdown(null, null);

        assertThat(result.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("An invalid period is 400 and never reaches TransactionAggregationService")
    void breakdown_invalidPeriod() {
        var service = at("2026-03-15T10:00:00Z");

        assertThatThrownBy(() -> service.categoryBreakdown("2026", null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(aggregationService);
    }

    // ---- monthly trend ----

    @Test
    @DisplayName("Trend makes one query for the current month and the five before it")
    void trend_singleQueryRange() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByMonth(LocalDate.of(2025, 10, 1), MAR_31)).thenReturn(List.of());

        service.monthlyTrend();

        verify(aggregationService, times(1)).debitTotalsByMonth(LocalDate.of(2025, 10, 1), MAR_31);
    }

    @Test
    @DisplayName("Trend always has six months, oldest first, across the year boundary, with missing months as 0.00")
    void trend_fillsMissingMonths() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByMonth(LocalDate.of(2025, 10, 1), MAR_31)).thenReturn(List.of(
                new MonthSpendingDto(2025, 12, new BigDecimal("40.00")),
                new MonthSpendingDto(2026, 1, new BigDecimal("15.50"))));

        var result = service.monthlyTrend();

        assertThat(result.months()).containsExactly(
                new MonthAmount(2025, 10, new BigDecimal("0.00")),
                new MonthAmount(2025, 11, new BigDecimal("0.00")),
                new MonthAmount(2025, 12, new BigDecimal("40.00")),
                new MonthAmount(2026, 1, new BigDecimal("15.50")),
                new MonthAmount(2026, 2, new BigDecimal("0.00")),
                new MonthAmount(2026, 3, new BigDecimal("0.00")));
    }

    @Test
    @DisplayName("Trend in January starts in August of the previous year")
    void trend_january() {
        var service = at("2026-01-05T00:00:00Z");
        when(aggregationService.debitTotalsByMonth(LocalDate.of(2025, 8, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(List.of());

        var result = service.monthlyTrend();

        assertThat(result.months()).extracting(MonthAmount::month).containsExactly(8, 9, 10, 11, 12, 1);
    }

    // ---- top categories ----

    @Test
    @DisplayName("Top categories without end covers today and the 29 days before")
    void top_defaultWindow() {
        var service = at("2026-03-15T10:00:00Z");
        var to = LocalDate.of(2026, 3, 15);
        var from = LocalDate.of(2026, 2, 14);
        when(aggregationService.debitTotalsByCategory(from, to)).thenReturn(List.of());

        var result = service.topCategories(null);

        assertThat(List.of(result.from(), result.to())).containsExactly(from, to);
    }

    @Test
    @DisplayName("Top categories keeps five, and total and percents use all spending in the window (D-12)")
    void top_fiveOfAll() {
        var service = at("2026-03-15T10:00:00Z");
        var to = LocalDate.of(2026, 1, 10);
        var from = LocalDate.of(2025, 12, 12);
        when(aggregationService.debitTotalsByCategory(from, to)).thenReturn(List.of(
                row(Category.GROCERIES, "60.00"), row(Category.DINING, "40.00"), row(Category.TRANSPORT, "30.00"),
                row(Category.UTILITIES, "30.00"), row(Category.ENTERTAINMENT, "20.00"), row(Category.OTHER, "20.00")));

        var result = service.topCategories("2026-01-10");

        assertThat(result.total()).isEqualTo(new BigDecimal("200.00"));
        assertThat(result.items()).containsExactly(
                item(Category.GROCERIES, "60.00", "30.00"),
                item(Category.DINING, "40.00", "20.00"),
                item(Category.TRANSPORT, "30.00", "15.00"),
                item(Category.UTILITIES, "30.00", "15.00"),
                item(Category.ENTERTAINMENT, "20.00", "10.00"));
    }

    @Test
    @DisplayName("Top categories with no spending returns total 0.00 and an empty list")
    void top_empty() {
        var service = at("2026-03-15T10:00:00Z");
        when(aggregationService.debitTotalsByCategory(LocalDate.of(2026, 2, 14), LocalDate.of(2026, 3, 15)))
                .thenReturn(List.of());

        var result = service.topCategories("");

        assertThat(result.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("An invalid end date is 400 and never reaches TransactionAggregationService")
    void top_invalidEnd() {
        var service = at("2026-03-15T10:00:00Z");

        assertThatThrownBy(() -> service.topCategories("2026-02-30")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(aggregationService);
    }
}
