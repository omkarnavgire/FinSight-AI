package com.finsight.cashflow.domain;

import com.finsight.shared.exception.BadRequestException;

/**
 * The forecast lengths a user may request (FR-06-1): 30, 60 or 90 days. 30 is the default.
 */
public enum ForecastHorizon {

    D30(30), D60(60), D90(90);

    private final int days;

    ForecastHorizon(int days) {
        this.days = days;
    }

    /** Number of forecast days. */
    public int days() {
        return days;
    }

    /**
     * Strict parse of the {@code horizon} query parameter (D-11).
     *
     * @param value {@code "30"}, {@code "60"} or {@code "90"}; {@code null} or blank means 30
     * @return the matching horizon
     * @throws BadRequestException for any other value; the message never echoes the input
     */
    public static ForecastHorizon parse(String value) {
        if (value == null || value.isBlank()) {
            return D30;
        }
        return switch (value.trim()) {
            case "30" -> D30;
            case "60" -> D60;
            case "90" -> D90;
            default -> throw new BadRequestException("horizon must be 30, 60 or 90");
        };
    }

    /**
     * The horizon stored in a snapshot row.
     *
     * @throws IllegalStateException if the stored value is not 30, 60 or 90
     */
    public static ForecastHorizon ofDays(int days) {
        for (var h : values()) {
            if (h.days == days) {
                return h;
            }
        }
        throw new IllegalStateException("Unknown horizon in snapshot");
    }
}
