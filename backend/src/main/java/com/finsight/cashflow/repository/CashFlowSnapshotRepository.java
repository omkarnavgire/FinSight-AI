package com.finsight.cashflow.repository;

import com.finsight.cashflow.domain.CashFlowSnapshot;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link CashFlowSnapshot}. Every query is scoped by {@code userId} (AC-OWN-2).
 *
 * <p>Extends the minimal {@link Repository}: unscoped {@code findById}/{@code findAll}/{@code deleteAll}
 * do not exist. Only cashflow-module code may use it ({@code ModuleBoundaryTest}).
 */
public interface CashFlowSnapshotRepository extends Repository<CashFlowSnapshot, Long> {

    /** Inserts a new snapshot. */
    CashFlowSnapshot save(CashFlowSnapshot snapshot);

    /**
     * Finds a snapshot only if it belongs to {@code userId} (T-18, AC-OWN-2).
     * Empty for a missing snapshot and for another user's snapshot alike.
     */
    Optional<CashFlowSnapshot> findByIdAndUserId(Long id, Long userId);

    /**
     * The user's newest snapshot for one horizon. Newest {@code generatedAt} first, then highest
     * {@code id}, so a rare duplicate from two concurrent first requests still reads one row.
     */
    Optional<CashFlowSnapshot> findFirstByUserIdAndHorizonDaysOrderByGeneratedAtDescIdDesc(Long userId, byte horizonDays);

    /**
     * Deletes all of the user's snapshots. Called only inside the replacement transaction, after
     * the new forecasts were built (D-9). Flushes pending changes first.
     *
     * @return number of rows deleted
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CashFlowSnapshot s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
