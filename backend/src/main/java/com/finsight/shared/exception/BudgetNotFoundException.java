package com.finsight.shared.exception;

/**
 * Thrown when a budget does not exist or belongs to another user.
 *
 * <p>Both cases map to HTTP 404 so another user's budget is never revealed (AC-OWN-B1, AC-OWN-B2).
 */
public class BudgetNotFoundException extends NotFoundException {

    public BudgetNotFoundException(Long budgetId) {
        super("Budget not found: " + budgetId);
    }
}
