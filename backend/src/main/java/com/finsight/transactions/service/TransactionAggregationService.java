package com.finsight.transactions.service;

import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CategorySpendingDto;
import com.finsight.transactions.dto.DatedAmountDto;
import com.finsight.transactions.dto.MonthSpendingDto;
import com.finsight.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Read-only views over the caller's transactions for other modules: spending totals
 * (Phase 7, analytics) and dated amounts (Phase 8, cash-flow).
 *
 * <p>The caller's identity always comes from {@link SecurityUtils#currentUserId()}; no method
 * accepts a user ID. Every query joins {@code accounts.user_id}, so another user's transactions
 * are never returned. In the two totals methods, "spending" means debits only ({@code amount < 0}),
 * grouped in the database and returned as absolute values. {@link #amountsBetween} returns
 * credits and debits with their sign. Transactions on deactivated accounts are included.
 */
@Service
public class TransactionAggregationService {

    /** D-11: longest aggregation range in calendar days, both ends included. */
    static final int MAX_AGGREGATION_DAYS = 366;

    private final TransactionRepository transactionRepository;

    public TransactionAggregationService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * The caller's total debits per category for an inclusive date range. Ordered by total
     * descending, then category name. Categories without debits are absent.
     *
     * @param from first day, inclusive
     * @param to   last day, inclusive; the range may span at most {@value #MAX_AGGREGATION_DAYS} days
     * @return one entry per category with spending; empty if none
     * @throws BadRequestException if a bound is missing, {@code from > to}, or the range is too long (400)
     */
    @Transactional(readOnly = true)
    public List<CategorySpendingDto> debitTotalsByCategory(LocalDate from, LocalDate to) {
        requireRange(from, to);
        return transactionRepository.debitTotalsByCategory(SecurityUtils.currentUserId(), from, to).stream()
                .map(row -> new CategorySpendingDto(Category.valueOf(row.getCategory()), row.getTotal()))
                .toList();
    }

    /**
     * The caller's total debits per calendar month for an inclusive date range. Oldest month
     * first. Months without debits are absent.
     *
     * @param from first day, inclusive
     * @param to   last day, inclusive; the range may span at most {@value #MAX_AGGREGATION_DAYS} days
     * @return one entry per month with spending; empty if none
     * @throws BadRequestException if a bound is missing, {@code from > to}, or the range is too long (400)
     */
    @Transactional(readOnly = true)
    public List<MonthSpendingDto> debitTotalsByMonth(LocalDate from, LocalDate to) {
        requireRange(from, to);
        return transactionRepository.debitTotalsByMonth(SecurityUtils.currentUserId(), from, to).stream()
                .map(row -> new MonthSpendingDto(
                        row.getPeriodYear().intValue(), row.getPeriodMonth().intValue(), row.getTotal()))
                .toList();
    }

    /**
     * Date and signed amount of each of the caller's transactions in an inclusive date range,
     * credits and debits alike (Phase 8, used by cash-flow). Oldest first.
     *
     * @param from first day, inclusive
     * @param to   last day, inclusive; the range may span at most {@value #MAX_AGGREGATION_DAYS} days
     * @return one entry per transaction; empty if none
     * @throws BadRequestException if a bound is missing, {@code from > to}, or the range is too long (400)
     */
    @Transactional(readOnly = true)
    public List<DatedAmountDto> amountsBetween(LocalDate from, LocalDate to) {
        requireRange(from, to);
        return transactionRepository.amountsBetween(SecurityUtils.currentUserId(), from, to).stream()
                .map(row -> new DatedAmountDto(toLocalDate(row.getTransactionDate()), row.getAmount()))
                .toList();
    }

    /** Accepts the date types a JDBC driver returns for a {@code DATE} column in a native query. */
    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            case null, default -> throw new IllegalStateException("Unexpected transaction_date type");
        };
    }

    /** Both bounds present, ordered, and within {@link #MAX_AGGREGATION_DAYS}. Checked before any SQL. */
    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_AGGREGATION_DAYS) {
            throw new BadRequestException("date range must not exceed " + MAX_AGGREGATION_DAYS + " days");
        }
    }
}
