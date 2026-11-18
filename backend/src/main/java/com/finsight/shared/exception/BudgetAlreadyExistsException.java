package com.finsight.shared.exception;

/**
 * Thrown when the caller already has a budget for the same category and period
 * ({@code UNIQUE (user_id, category, period_year, period_month)} in V5).
 *
 * <p>Maps to HTTP 409 Conflict.
 */
public class BudgetAlreadyExistsException extends ConflictException {

    public BudgetAlreadyExistsException() {
        super("A budget already exists for this category and period");
    }
}
