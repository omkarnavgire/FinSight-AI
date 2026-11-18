package com.finsight.transactions.repository;

import com.finsight.transactions.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Repository for {@link Transaction}. Every read is scoped to the owning user (AC-OWN-2).
 *
 * <p>{@code transactions} has no {@code user_id}; ownership is resolved by joining
 * {@code accounts.user_id} (design.md: "All transaction queries join or sub-select on accounts").
 * The join is native SQL, so no accounts-module Java class is referenced.
 *
 * <p>Extends the minimal {@link Repository}: unscoped {@code findById}/{@code findAll} do not exist.
 * Only transactions-module code may use this repository ({@code ModuleBoundaryTest}).
 */
public interface TransactionRepository extends Repository<Transaction, Long> {

    /** Inserts a new transaction, or flushes changes to an already-managed one. */
    Transaction save(Transaction transaction);

    /** Deletes a managed transaction. */
    void delete(Transaction transaction);

    /**
     * Finds a transaction only if its account belongs to {@code userId}.
     * Empty for a missing transaction and for another user's transaction alike.
     */
    @Query(value = """
            SELECT t.*
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE t.id = :id
               AND a.user_id = :userId
            """, nativeQuery = true)
    Optional<Transaction> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Same as {@link #findByIdAndUserId}, but takes a row lock on the transaction row only
     * ({@code FOR UPDATE OF t}, MySQL 8.0.1+). Used by edit and delete so two concurrent
     * operations on one transaction cannot both compute a delta from the same old amount.
     * Must be called inside a transaction.
     */
    @Query(value = """
            SELECT t.*
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE t.id = :id
               AND a.user_id = :userId
               FOR UPDATE OF t
            """, nativeQuery = true)
    Optional<Transaction> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Pages through the user's transactions with optional filters (FR-03-1, FR-03-2).
     * A {@code null} filter is ignored. Order is fixed: newest {@code transaction_date} first,
     * then highest {@code id}, which keeps pages stable. Sorting in {@code pageable} is not used.
     *
     * @param category enum name (e.g. {@code "DINING"}) or {@code null}
     */
    @Query(value = """
            SELECT t.*
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND (:accountId IS NULL OR t.account_id = :accountId)
               AND (:fromDate  IS NULL OR t.transaction_date >= :fromDate)
               AND (:toDate    IS NULL OR t.transaction_date <= :toDate)
               AND (:category  IS NULL OR t.category = :category)
               AND (:minAmount IS NULL OR t.amount >= :minAmount)
               AND (:maxAmount IS NULL OR t.amount <= :maxAmount)
             ORDER BY t.transaction_date DESC, t.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND (:accountId IS NULL OR t.account_id = :accountId)
               AND (:fromDate  IS NULL OR t.transaction_date >= :fromDate)
               AND (:toDate    IS NULL OR t.transaction_date <= :toDate)
               AND (:category  IS NULL OR t.category = :category)
               AND (:minAmount IS NULL OR t.amount >= :minAmount)
               AND (:maxAmount IS NULL OR t.amount <= :maxAmount)
            """,
            nativeQuery = true)
    Page<Transaction> search(@Param("userId") Long userId,
                             @Param("accountId") Long accountId,
                             @Param("fromDate") LocalDate fromDate,
                             @Param("toDate") LocalDate toDate,
                             @Param("category") String category,
                             @Param("minAmount") BigDecimal minAmount,
                             @Param("maxAmount") BigDecimal maxAmount,
                             Pageable pageable);
}
