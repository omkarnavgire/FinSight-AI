package com.finsight.budgeting.service;

import com.finsight.shared.exception.BadRequestException;

import java.time.Clock;
import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * Strict parsing of budget periods (year and month).
 *
 * <p>Rules: both or neither; neither means the current period from the {@link Clock};
 * year 2000–2100; month 1–12. Every violation is a {@link BadRequestException} (400 ProblemDetail).
 * Query parameters arrive as strings so that non-numeric input also yields a ProblemDetail
 * instead of an unmapped type-conversion error. Messages never echo the submitted value.
 */
public final class PeriodParser {

    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,4}$");

    private PeriodParser() {}

    /**
     * Parses optional {@code year}/{@code month} query parameters.
     *
     * @return the requested period, or the current period when both are absent
     * @throws BadRequestException if only one is given, or either is not a valid number in range
     */
    public static YearMonth parse(String year, String month, Clock clock) {
        boolean hasYear = year != null && !year.isBlank();
        boolean hasMonth = month != null && !month.isBlank();
        if (!hasYear && !hasMonth) {
            return YearMonth.now(clock);
        }
        if (hasYear != hasMonth) {
            throw new BadRequestException("year and month must be supplied together");
        }
        return of(toInt(year.trim(), "year"), toInt(month.trim(), "month"));
    }

    /**
     * Resolves an optional period from a request body.
     *
     * @return the given period, or the current period when both are {@code null}
     * @throws BadRequestException if only one is given or either is out of range
     */
    public static YearMonth resolve(Integer year, Integer month, Clock clock) {
        if (year == null && month == null) {
            return YearMonth.now(clock);
        }
        if (year == null || month == null) {
            throw new BadRequestException("periodYear and periodMonth must be supplied together");
        }
        return of(year, month);
    }

    private static YearMonth of(int year, int month) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new BadRequestException("year must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        return YearMonth.of(year, month);
    }

    private static int toInt(String value, String name) {
        if (!DIGITS.matcher(value).matches()) {
            throw new BadRequestException(name + " must be a number");
        }
        return Integer.parseInt(value);
    }
}
