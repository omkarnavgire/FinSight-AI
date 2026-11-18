package com.finsight.budgeting.service;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.TransactionFilter;
import com.finsight.transactions.service.TransactionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Computes the caller's spending in one category and calendar month (D-3).
 *
 * <p>Spending = the sum of the absolute values of <em>debits</em> (amount &lt; 0) dated in that month,
 * across all of the caller's accounts (including deactivated ones). Credits and refunds are ignored.
 *
 * <p>Uses only the public {@link TransactionService#search} API (scope correction C-1), so it is scoped
 * to the security-context user exactly like the transactions list endpoint. When called inside a
 * transaction, all pages are read from one consistent snapshot.
 */
@Component
public class SpendingCalculator {

    /** Upper amount bound that selects debits only (amounts have 2 decimal places). */
    static final BigDecimal DEBITS_ONLY_MAX = new BigDecimal("-0.01");
    static final int PAGE_SIZE = 100;

    private final TransactionService transactionService;

    public SpendingCalculator(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Returns the caller's total debits for a category in a month, as a non-negative amount.
     *
     * @param category budgeted category
     * @param period   calendar month
     * @return total spent, {@code 0} if nothing matches
     */
    public BigDecimal debitTotal(Category category, YearMonth period) {
        var filter = new TransactionFilter(
                null, period.atDay(1), period.atEndOfMonth(), category, null, DEBITS_ONLY_MAX);
        var total = BigDecimal.ZERO;
        int page = 0;
        while (true) {
            var result = transactionService.search(filter, page, PAGE_SIZE);
            for (var tx : result.content()) {
                total = total.add(tx.amount().negate());
            }
            if (result.last() || result.content().isEmpty()) {
                return total;
            }
            page++;
        }
    }
}
