package com.finsight.fraud.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Read-only view of the signed-in user's existing transactions, used by the fraud rules (Q-1).
 *
 * <p>Defined by the fraud module and implemented by the transactions module, so fraud never
 * depends on transactions. Every method is scoped to the user in the security context and reads
 * only committed or same-transaction rows that already exist: the transaction being evaluated is
 * not inserted yet, so it is never part of the history.
 *
 * <p>Implementations must not write and must not start their own transaction.
 */
public interface FraudHistory {

    /**
     * {@code created_at} of the newest existing transaction on {@code accountId} with exactly this
     * amount and description ({@code null} matches {@code null}, Q-4).
     *
     * <p>The value is read through the same mapping that wrote it, so it compares correctly with
     * {@link LocalDateTime#now()} (C-1).
     *
     * @return the newest matching creation time, or empty if there is none
     */
    Optional<LocalDateTime> latestDuplicateCreatedAt(Long accountId, BigDecimal amount, String description);

    /**
     * Count and signed sum of the user's transactions in {@code category} whose
     * {@code transaction_date} is in {@code from … to} inclusive, across all of the user's accounts.
     */
    CategoryStats categoryStats(String category, LocalDate from, LocalDate to);

    /** {@code true} if the user has at least one transaction on any of their accounts. */
    boolean hasAnyTransaction();

    /** {@code true} if any of the user's transactions, on any account and at any time, used {@code countryCode}. */
    boolean hasUsedCountry(String countryCode);

    /**
     * Category totals for the abnormal-amount rule.
     *
     * @param count number of transactions ({@code n})
     * @param sum   signed sum of their amounts ({@code s}); {@code 0} when {@code count} is 0
     */
    record CategoryStats(long count, BigDecimal sum) {
    }
}
