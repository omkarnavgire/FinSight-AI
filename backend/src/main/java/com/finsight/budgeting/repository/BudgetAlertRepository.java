package com.finsight.budgeting.repository;

import com.finsight.budgeting.domain.BudgetAlert;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link BudgetAlert}.
 *
 * <p>{@code budget_alerts} has no {@code user_id}; every query joins {@code budgets.user_id}
 * (design.md "Budgets and BudgetAlerts ownership"). No method accepts a bare alert ID (AC-OWN-B3).
 */
public interface BudgetAlertRepository extends Repository<BudgetAlert, Long> {

    /** Fired thresholds (80 / 100) of the user's budget, ascending. Empty if not owned. */
    @Query(value = """
            SELECT ba.threshold_pct
              FROM budget_alerts ba
              JOIN budgets b ON b.id = ba.budget_id
             WHERE b.id = :budgetId
               AND b.user_id = :userId
             ORDER BY ba.threshold_pct
            """, nativeQuery = true)
    List<Number> firedThresholds(@Param("budgetId") Long budgetId, @Param("userId") Long userId);

    /**
     * Records a fired threshold at most once.
     *
     * <p>The owner is checked inside the statement: nothing is inserted unless the budget belongs to
     * {@code userId}. {@code INSERT IGNORE} plus {@code UNIQUE (budget_id, threshold_pct)} makes
     * repeated or concurrent calls safe. The {@code SELECT} takes a shared lock on the budget row,
     * so it waits for a concurrent delete and then inserts nothing.
     *
     * @return 1 if newly fired, 0 if already fired or not owned
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO budget_alerts (budget_id, threshold_pct)
            SELECT b.id, :pct
              FROM budgets b
             WHERE b.id = :budgetId
               AND b.user_id = :userId
            """, nativeQuery = true)
    int fireIfAbsent(@Param("budgetId") Long budgetId, @Param("userId") Long userId, @Param("pct") int pct);

    /** Deletes all alerts of the user's budget. Returns the number of rows deleted. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE ba
              FROM budget_alerts ba
              JOIN budgets b ON b.id = ba.budget_id
             WHERE b.id = :budgetId
               AND b.user_id = :userId
            """, nativeQuery = true)
    int deleteForBudget(@Param("budgetId") Long budgetId, @Param("userId") Long userId);
}
