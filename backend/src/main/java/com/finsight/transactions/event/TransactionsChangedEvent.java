package com.finsight.transactions.event;

import com.finsight.transactions.domain.Category;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/**
 * Published by {@code TransactionService} inside its transaction whenever committed data may change
 * a user's spending: create, update, delete and category override.
 *
 * <p>Listeners should use {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so they only see
 * committed data, and nothing is delivered for a rolled-back transaction.
 *
 * <p>{@code userId} is resolved server-side from the security context, never from client input.
 *
 * @param userId   owner of the changed transactions
 * @param affected every (category, month) whose spending may have changed; an edit lists both the
 *                 old and the new combination when they differ
 */
public record TransactionsChangedEvent(Long userId, Set<CategoryPeriod> affected) {

    public TransactionsChangedEvent {
        affected = Set.copyOf(affected);
    }

    /**
     * A category in one calendar month.
     *
     * @param category transaction category
     * @param period   calendar month of the transaction date
     */
    public record CategoryPeriod(Category category, YearMonth period) {

        /** Builds the pair for a transaction's category and date. */
        public static CategoryPeriod of(Category category, LocalDate date) {
            return new CategoryPeriod(category, YearMonth.from(date));
        }
    }
}
