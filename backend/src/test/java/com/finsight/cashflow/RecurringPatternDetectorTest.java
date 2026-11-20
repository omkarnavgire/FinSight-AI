package com.finsight.cashflow;

import com.finsight.cashflow.service.RecurringPatternDetector;
import com.finsight.cashflow.service.RecurringPatternDetector.RecurringItem;
import com.finsight.transactions.dto.DatedAmountDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link RecurringPatternDetector} (T-44, D-6). */
class RecurringPatternDetectorTest {

    private final RecurringPatternDetector detector = new RecurringPatternDetector();

    private static DatedAmountDto tx(String date, String amount) {
        return new DatedAmountDto(LocalDate.parse(date), new BigDecimal(amount));
    }

    private static RecurringItem item(int day, String amount) {
        return new RecurringItem(day, new BigDecimal(amount));
    }

    @Test
    @DisplayName("Monthly salary and rent across three months are both detected")
    void detectsSalaryAndRent() {
        var result = detector.detect(List.of(
                tx("2026-01-01", "3000.00"), tx("2026-02-01", "3000.00"), tx("2026-03-01", "3000.00"),
                tx("2026-01-05", "-1200.00"), tx("2026-02-05", "-1200.00"), tx("2026-03-05", "-1200.00"),
                tx("2026-02-17", "-45.90")));   // one-off, not recurring

        assertThat(result).containsExactly(item(1, "3000.00"), item(5, "-1200.00"));
    }

    @Test
    @DisplayName("Amounts exactly 2 % apart match: max <= min * 1.02")
    void amountExactlyTwoPercent_matches() {
        var result = detector.detect(List.of(tx("2026-01-10", "-100.00"), tx("2026-02-10", "-102.00")));

        assertThat(result).containsExactly(item(10, "-101.00"));
    }

    @Test
    @DisplayName("Amounts just over 2 % apart do not match")
    void amountOverTwoPercent_noMatch() {
        assertThat(detector.detect(List.of(tx("2026-01-10", "-100.00"), tx("2026-02-10", "-102.01")))).isEmpty();
    }

    @Test
    @DisplayName("Days of month 2 apart match; 3 apart do not")
    void dayTolerance() {
        assertThat(detector.detect(List.of(tx("2026-01-15", "50.00"), tx("2026-02-17", "50.00"))))
                .containsExactly(item(17, "50.00"));
        assertThat(detector.detect(List.of(tx("2026-01-15", "50.00"), tx("2026-02-18", "50.00")))).isEmpty();
    }

    @Test
    @DisplayName("Two occurrences in the same month are not recurring")
    void sameMonth_notRecurring() {
        assertThat(detector.detect(List.of(tx("2026-03-01", "-20.00"), tx("2026-03-02", "-20.00")))).isEmpty();
    }

    @Test
    @DisplayName("A credit and a debit of the same size are never grouped")
    void signsNeverMixed() {
        assertThat(detector.detect(List.of(tx("2026-01-10", "75.00"), tx("2026-02-10", "-75.00")))).isEmpty();
    }

    @Test
    @DisplayName("The day of the most recent occurrence is used")
    void latestDayUsed() {
        var result = detector.detect(List.of(
                tx("2026-01-28", "-9.99"), tx("2026-02-27", "-9.99"), tx("2026-03-29", "-9.99")));

        assertThat(result).containsExactly(item(29, "-9.99"));
    }

    @Test
    @DisplayName("Empty input gives no recurring items")
    void emptyInput() {
        assertThat(detector.detect(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Less than 30 days of input inside one month gives no recurring items")
    void shortHistory_singleMonth() {
        assertThat(detector.detect(List.of(
                tx("2026-03-02", "-30.00"), tx("2026-03-09", "-30.00"), tx("2026-03-16", "-30.00")))).isEmpty();
    }

    @Test
    @DisplayName("Less than 30 days of input across a month boundary can still be recurring")
    void shortHistory_acrossMonthBoundary() {
        var result = detector.detect(List.of(tx("2026-02-27", "-15.00"), tx("2026-03-28", "-15.00")));

        assertThat(result).containsExactly(item(28, "-15.00"));
    }

    @Test
    @DisplayName("Day 27 and day 1 are not matched: there is no wrap-around at month end")
    void noWrapAround() {
        assertThat(detector.detect(List.of(tx("2026-02-27", "-15.00"), tx("2026-03-01", "-15.00")))).isEmpty();
    }
}
