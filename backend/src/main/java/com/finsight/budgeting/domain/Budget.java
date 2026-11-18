package com.finsight.budgeting.domain;

import com.finsight.transactions.domain.Category;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * A monthly spending limit for one category in one calendar month ({@code budgets}, V5).
 *
 * <p>{@code userId} is a plain ID: {@code users} belongs to the auth module (no cross-module FK).
 * Only {@code monthlyLimit} can change after creation (FR-04-4).
 */
@Entity
@Table(name = "budgets")
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private Category category;

    @Column(name = "monthly_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyLimit;

    @Column(name = "period_year", nullable = false, updatable = false)
    private short periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private byte periodMonth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Budget() {}

    /**
     * Creates a new budget.
     *
     * @param userId       owner, from the security context
     * @param category     budgeted category
     * @param monthlyLimit positive limit
     * @param period       calendar month the budget covers
     * @return a new, unsaved budget
     */
    public static Budget create(Long userId, Category category, BigDecimal monthlyLimit, YearMonth period) {
        var b = new Budget();
        b.userId = userId;
        b.category = category;
        b.monthlyLimit = monthlyLimit;
        b.periodYear = (short) period.getYear();
        b.periodMonth = (byte) period.getMonthValue();
        b.createdAt = LocalDateTime.now();
        return b;
    }

    /** Changes the monthly limit (FR-04-4). */
    public void updateLimit(BigDecimal newLimit) {
        this.monthlyLimit = newLimit;
    }

    /** The calendar month this budget covers. */
    public YearMonth period() {
        return YearMonth.of(periodYear, periodMonth);
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    public Category getCategory() { return category; }

    public BigDecimal getMonthlyLimit() { return monthlyLimit; }

    public short getPeriodYear() { return periodYear; }

    public byte getPeriodMonth() { return periodMonth; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
