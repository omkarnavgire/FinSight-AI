package com.finsight.budgeting.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A fired budget threshold ({@code budget_alerts}, V5): 80 or 100 percent of the limit.
 *
 * <p>Owned transitively through {@code budgets.user_id}. Rows are written only by the
 * owner-checked {@code INSERT IGNORE} in {@code BudgetAlertRepository}, so the unique key
 * {@code (budget_id, threshold_pct)} guarantees each threshold fires at most once (FR-04-3).
 * The entity is read-only.
 */
@Entity
@Table(name = "budget_alerts")
public class BudgetAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_id", nullable = false, updatable = false, insertable = false)
    private Long budgetId;

    @Column(name = "threshold_pct", nullable = false, updatable = false, insertable = false)
    private byte thresholdPct;

    @Column(name = "fired_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime firedAt;

    protected BudgetAlert() {}

    public Long getId() { return id; }

    public Long getBudgetId() { return budgetId; }

    public byte getThresholdPct() { return thresholdPct; }

    public LocalDateTime getFiredAt() { return firedAt; }
}
