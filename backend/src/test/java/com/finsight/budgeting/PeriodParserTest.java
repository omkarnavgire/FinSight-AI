package com.finsight.budgeting;

import com.finsight.budgeting.service.PeriodParser;
import com.finsight.shared.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Strict period parsing for budget progress and creation. */
class PeriodParserTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Neither year nor month means the current period from the clock")
    void neither_isCurrentPeriod() {
        assertThat(PeriodParser.parse(null, null, FIXED)).isEqualTo(YearMonth.of(2026, 7));
        assertThat(PeriodParser.parse("", " ", FIXED)).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    @DisplayName("Both valid gives that period")
    void both_valid() {
        assertThat(PeriodParser.parse("2025", "3", FIXED)).isEqualTo(YearMonth.of(2025, 3));
        assertThat(PeriodParser.parse("2025", "03", FIXED)).isEqualTo(YearMonth.of(2025, 3));
    }

    @ParameterizedTest(name = "year={0}, month={1}")
    @CsvSource(value = {
            "2025, NULL", "NULL, 3",              // only one supplied
            "abc, 3", "2025, x", "2025.0, 3",     // not a number
            "-2025, 3", "2025, -1",               // signs not allowed
            "2025, 0", "2025, 13",                // month out of range
            "1999, 1", "2101, 1", "99999, 1"      // year out of range / too long
    }, nullValues = "NULL")
    @DisplayName("Incomplete, non-numeric or out-of-range input is a 400")
    void invalid_isBadRequest(String year, String month) {
        assertThatThrownBy(() -> PeriodParser.parse(year, month, FIXED)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("Error messages do not echo the submitted value")
    void message_doesNotEchoInput() {
        assertThatThrownBy(() -> PeriodParser.parse("<script>", "3", FIXED))
                .isInstanceOf(BadRequestException.class)
                .hasMessageNotContaining("<script>");
    }

    @Test
    @DisplayName("resolve: both null is current, one null is a 400, both set is that period")
    void resolve_rules() {
        assertThat(PeriodParser.resolve(null, null, FIXED)).isEqualTo(YearMonth.of(2026, 7));
        assertThat(PeriodParser.resolve(2026, 2, FIXED)).isEqualTo(YearMonth.of(2026, 2));
        assertThatThrownBy(() -> PeriodParser.resolve(2026, null, FIXED)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PeriodParser.resolve(null, 2, FIXED)).isInstanceOf(BadRequestException.class);
    }
}
