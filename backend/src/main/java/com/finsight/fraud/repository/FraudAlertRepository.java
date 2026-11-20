package com.finsight.fraud.repository;

import com.finsight.fraud.domain.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Repository for {@link FraudAlert}. Every read is scoped by {@code userId} (AC-OWN-2, T-18).
 *
 * <p>Extends the minimal {@link Repository}: unscoped {@code findById}/{@code findAll} do not exist.
 * Only fraud-module code may use it ({@code ModuleBoundaryTest}).
 */
public interface FraudAlertRepository extends Repository<FraudAlert, Long> {

    /** Inserts a new alert (IDENTITY key: the INSERT runs immediately), or flushes a managed one. */
    FraudAlert save(FraudAlert alert);

    /** Finds an alert only if it belongs to {@code userId}. Empty for missing and foreign alike (T-18). */
    Optional<FraudAlert> findByIdAndUserId(Long id, Long userId);

    /** The user's unacknowledged alerts, newest first, then highest {@code id} for a stable order. */
    Page<FraudAlert> findByUserIdAndAcknowledgedFalseOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
