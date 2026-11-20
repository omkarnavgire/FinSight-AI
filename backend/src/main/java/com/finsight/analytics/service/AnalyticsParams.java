package com.finsight.analytics.service;

import com.finsight.shared.exception.BadRequestException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * Strict parsing of analytics query parameters (D-7). Local to the analytics module.
 *
 * <p>Parameters arrive as strings so every invalid value becomes a {@link BadRequestException}
 * (400 ProblemDetail) instead of a type-conversion error. Messages never echo the submitted value.
 */
public final class AnalyticsParams {

    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,4}$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private AnalyticsParams() {}

    /**
     * Parses optional {@code year}/{@code month}: both or neither; neither means the current month.
     *
     * @return the requested month, or the current month from {@code clock}
     * @throws BadRequestException if only one is given, or either is not a number in range
     */
    public static YearMonth month(String year, String month, Clock clock) {
        var hasYear = year != null && !year.isBlank();
        var hasMonth = month != null && !month.isBlank();
        if (!hasYear && !hasMonth) {
            return YearMonth.now(clock);
        }
        if (hasYear != hasMonth) {
            throw new BadRequestException("year and month must be supplied together");
        }
        var y = toInt(year.trim(), "year");
        var m = toInt(month.trim(), "month");
        requireYear(y);
        if (m < 1 || m > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        return YearMonth.of(y, m);
    }

    /**
     * Parses an optional {@code end} date in {@code YYYY-MM-DD} form; absent or blank means today.
     *
     * @return the requested date, or today from {@code clock}
     * @throws BadRequestException if the format is wrong, the date does not exist, or the year is out of range
     */
    public static LocalDate endDate(String end, Clock clock) {
        if (end == null || end.isBlank()) {
            return LocalDate.now(clock);
        }
        var value = end.trim();
        if (!ISO_DATE.matcher(value).matches()) {
            throw new BadRequestException("end must be a date in YYYY-MM-DD form");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value, STRICT_DATE);
        } catch (DateTimeException e) {
            throw new BadRequestException("end must be a valid calendar date");
        }
        requireYear(date.getYear());
        return date;
    }

    private static void requireYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new BadRequestException("year must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
    }

    private static int toInt(String value, String name) {
        if (!DIGITS.matcher(value).matches()) {
            throw new BadRequestException(name + " must be a number");
        }
        return Integer.parseInt(value);
    }
}
