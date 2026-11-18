package com.finsight.budgeting.repository;

import com.finsight.budgeting.domain.Budget;
import com.finsight.transactions.domain.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Budget}. Every query is scoped by {@code userId} (AC-OWN-2, AC-OWN-B1).
 *
 * <p>Extends the minimal {@link Repository}: unscoped {@code findById}/{@code findAll}/{@code deleteById}
 * do not exist. Only budgeting-module code may use it ({@code ModuleBoundaryTest}).
 */
public interface BudgetRepository extends Repository<Budget, Long> {

    /** Saves and flushes, so a unique-key violation surfaces inside the calling service method. */
    Budget saveAndFlush(Budget budget);

    /** Deletes a managed budget. Its alerts must be deleted first (FK without cascade). */
    void delete(Budget budget);

    /** Finds a budget only if it belongs to {@code userId}. Empty for missing and foreign alike. */
    Optional<Budget> findByIdAndUserId(Long id, Long userId);

    /**
     * Same as {@link #findByIdAndUserId} with a row lock ({@code SELECT … FOR UPDATE}).
     * Used by delete so a concurrent alert insert waits and then sees the budget gone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Budget b WHERE b.id = :id AND b.userId = :userId")
    Optional<Budget> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** The user's budgets for one period, ordered by category. */
    List<Budget> findByUserIdAndPeriodYearAndPeriodMonthOrderByCategoryAsc(Long userId, short periodYear, byte periodMonth);

    /** The user's budget for one category and period, if any (at most one, by the unique key). */
    Optional<Budget> findByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
            Long userId, Category category, short periodYear, byte periodMonth);

    /** {@code true} if the user already has a budget for this category and period. */
    boolean existsByUserIdAndCategoryAndPeriodYearAndPeriodMonth(
            Long userId, Category category, short periodYear, byte periodMonth);
}
