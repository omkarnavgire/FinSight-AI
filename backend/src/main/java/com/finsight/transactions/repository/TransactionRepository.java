package com.finsight.transactions.repository;

import com.finsight.transactions.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    // ---- Aggregations (Phase 7, analytics) ----

    /** One row of {@link #debitTotalsByCategory}. Internal to the transactions module. */
    interface CategoryTotalRow {
        String getCategory();
        BigDecimal getTotal();
    }

    /** One row of {@link #debitTotalsByMonth}. Internal to the transactions module. */
    interface MonthTotalRow {
        Number getPeriodYear();
        Number getPeriodMonth();
        BigDecimal getTotal();
    }

    /**
     * One row of {@link #amountsBetween}. Internal to the transactions module. The date is
     * typed {@code Object} because the JDBC driver may return {@code java.sql.Date} or
     * {@code LocalDate} for a native query; the service converts it.
     */
    interface DatedAmountRow {
        Object getTransactionDate();
        BigDecimal getAmount();
    }

    /**
     * The user's total debits per category, as absolute amounts, for an inclusive date range.
     * Owner-scoped through {@code accounts.user_id}; credits are excluded. {@code transaction_date}
     * is a {@code DATE} column, so {@code BETWEEN} includes the whole last day.
     * Ordered by total descending, then category name.
     */
    @Query(value = """
            SELECT t.category         AS category,
                   SUM(ABS(t.amount)) AS total
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND t.amount < 0
               AND t.transaction_date BETWEEN :fromDate AND :toDate
             GROUP BY t.category
             ORDER BY total DESC, t.category ASC
            """, nativeQuery = true)
    List<CategoryTotalRow> debitTotalsByCategory(@Param("userId") Long userId,
                                                 @Param("fromDate") LocalDate fromDate,
                                                 @Param("toDate") LocalDate toDate);

    /**
     * The user's total debits per calendar month, as absolute amounts, for an inclusive date range.
     * Owner-scoped through {@code accounts.user_id}; credits are excluded. Oldest month first.
     */
    @Query(value = """
            SELECT YEAR(t.transaction_date)  AS periodYear,
                   MONTH(t.transaction_date) AS periodMonth,
                   SUM(ABS(t.amount))        AS total
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND t.amount < 0
               AND t.transaction_date BETWEEN :fromDate AND :toDate
             GROUP BY YEAR(t.transaction_date), MONTH(t.transaction_date)
             ORDER BY periodYear ASC, periodMonth ASC
            """, nativeQuery = true)
    List<MonthTotalRow> debitTotalsByMonth(@Param("userId") Long userId,
                                           @Param("fromDate") LocalDate fromDate,
                                           @Param("toDate") LocalDate toDate);

    /**
     * Date and signed amount of every one of the user's transactions in an inclusive date range,
     * credits and debits alike (Phase 8, cash-flow). Owner-scoped through {@code accounts.user_id}.
     * Oldest first, then by {@code id}.
     */
    @Query(value = """
            SELECT t.transaction_date AS transactionDate,
                   t.amount           AS amount
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND t.transaction_date BETWEEN :fromDate AND :toDate
             ORDER BY t.transaction_date ASC, t.id ASC
            """, nativeQuery = true)
    List<DatedAmountRow> amountsBetween(@Param("userId") Long userId,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    // ---- Fraud history (Phase 9; read-only, owner-scoped through accounts.user_id) ----

    /**
     * The user's newest transaction on {@code accountId} with this amount and description
     * ({@code <=>} is MySQL's null-safe equals, so {@code NULL} matches {@code NULL}, Q-4).
     * Mapped to the entity, so {@code createdAt} is read through the same mapping that wrote it (C-1).
     * No database clock is used.
     */
    @Query(value = """
            SELECT t.*
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND t.account_id = :accountId
               AND t.amount = :amount
               AND t.description <=> :description
             ORDER BY t.created_at DESC, t.id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Transaction> findLatestSameAmountAndDescription(@Param("userId") Long userId,
                                                              @Param("accountId") Long accountId,
                                                              @Param("amount") BigDecimal amount,
                                                              @Param("description") String description);

    /** One row of {@link #categoryStats}. Internal to the transactions module. */
    interface CategoryStatsRow {
        Number getTxCount();
        BigDecimal getTotal();
    }

    /**
     * Count and signed sum of the user's transactions in one category, across all accounts, with
     * {@code transaction_date} in an inclusive range (abnormal-amount rule, C-2).
     */
    @Query(value = """
            SELECT COUNT(*)                   AS txCount,
                   COALESCE(SUM(t.amount), 0) AS total
              FROM transactions t
              JOIN accounts a ON a.id = t.account_id
             WHERE a.user_id = :userId
               AND t.category = :category
               AND t.transaction_date BETWEEN :fromDate AND :toDate
            """, nativeQuery = true)
    CategoryStatsRow categoryStats(@Param("userId") Long userId,
                                   @Param("category") String category,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate);

    /** {@code 1} if the user has any transaction on any account, otherwise {@code 0}. */
    @Query(value = """
            SELECT EXISTS (SELECT 1
                             FROM transactions t
                             JOIN accounts a ON a.id = t.account_id
                            WHERE a.user_id = :userId)
            """, nativeQuery = true)
    Number anyTransactionFlag(@Param("userId") Long userId);

    /** {@code 1} if any of the user's transactions, on any account, used {@code countryCode}, otherwise {@code 0}. */
    @Query(value = """
            SELECT EXISTS (SELECT 1
                             FROM transactions t
                             JOIN accounts a ON a.id = t.account_id
                            WHERE a.user_id = :userId
                              AND t.country_code = :countryCode)
            """, nativeQuery = true)
    Number usedCountryFlag(@Param("userId") Long userId, @Param("countryCode") String countryCode);
}
