package com.finsight.analytics;

import com.finsight.analytics.service.AnalyticsParams;
import com.finsight.shared.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AnalyticsParams} (D-7). */
class AnalyticsParamsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);

    // ---- month ----

    @Test
    @DisplayName("No year and month means the current month from the clock")
    void month_absent_current() {
        assertThat(AnalyticsParams.month(null, null, CLOCK)).isEqualTo(YearMonth.of(2026, 3));
        assertThat(AnalyticsParams.month(" ", "", CLOCK)).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    @DisplayName("Valid year and month are parsed, including the range limits")
    void month_valid() {
        assertThat(AnalyticsParams.month("2025", "12", CLOCK)).isEqualTo(YearMonth.of(2025, 12));
        assertThat(AnalyticsParams.month("2000", "1", CLOCK)).isEqualTo(YearMonth.of(2000, 1));
        assertThat(AnalyticsParams.month("2100", "07", CLOCK)).isEqualTo(YearMonth.of(2100, 7));
    }

    @ParameterizedTest(name = "year={0} month={1}")
    @CsvSource(nullValues = "NULL", value = {
            "2026, NULL", "NULL, 3", "abc, 3", "2026, x", "2026, 13", "2026, 0",
            "1999, 3", "2101, 3", "-2026, 3", "2026, -1", "20260, 1", "2026, 1.5"})
    @DisplayName("Incomplete, non-numeric or out-of-range periods are 400")
    void month_invalid(String year, String month) {
        assertThatThrownBy(() -> AnalyticsParams.month(year, month, CLOCK))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("Error messages never echo the submitted value")
    void month_messageDoesNotEcho() {
        assertThatThrownBy(() -> AnalyticsParams.month("<script>", "3", CLOCK))
                .isInstanceOf(BadRequestException.class)
                .message().doesNotContain("<script>");
    }

    // ---- end date ----

    @Test
    @DisplayName("No end date means today from the clock")
    void end_absent_today() {
        assertThat(AnalyticsParams.endDate(null, CLOCK)).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(AnalyticsParams.endDate("  ", CLOCK)).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    @DisplayName("A valid YYYY-MM-DD end date is parsed, including 29 February in a leap year")
    void end_valid() {
        assertThat(AnalyticsParams.endDate("2026-01-31", CLOCK)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(AnalyticsParams.endDate("2024-02-29", CLOCK)).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @ParameterizedTest(name = "end={0}")
    @ValueSource(strings = {"2026-02-30", "2025-02-29", "2026-13-01", "2026-00-10", "2026-1-5",
            "26-01-05", "2026/01/05", "20260105", "2026-01-05T00:00", "abc", "1999-12-31", "2101-01-01"})
    @DisplayName("Malformed, impossible or out-of-range end dates are 400")
    void end_invalid(String end) {
        assertThatThrownBy(() -> AnalyticsParams.endDate(end, CLOCK))
                .isInstanceOf(BadRequestException.class)
                .message().doesNotContain(end);
    }
}
