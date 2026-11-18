package com.finsight.budgeting.event;

/**
 * Published inside the transaction that creates a budget or changes its limit. Handled after
 * commit so alert evaluation sees the committed budget (budgeting-internal).
 *
 * @param budgetId changed budget
 * @param userId   owner, resolved server-side from the security context
 */
public record BudgetChangedEvent(Long budgetId, Long userId) {}
