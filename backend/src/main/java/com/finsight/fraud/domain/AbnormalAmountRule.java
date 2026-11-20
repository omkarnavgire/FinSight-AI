package com.finsight.fraud.domain;

import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Rule 2, abnormal amount (FR-07-2, AC-FRAUD-2, AC-FRAUD-2b, AC-FRAUD-2c, Q-5, C-2).
 *
 * <p>History: the user's existing transactions in the same category, on all of their accounts,
 * with {@code transaction_date} in {@code today-29 … today} ({@value #WINDOW_DAYS} days). The new
 * transaction is not inserted yet, so it is never part of the history. With {@code n} the count
 * and {@code s} the signed sum:
 * <ol>
 *   <li>skip if {@code n < }{@value #MIN_HISTORY};</li>
 *   <li>skip if {@code s = 0} (the signed mean is zero);</li>
 *   <li>fire only if {@code sign(amount) = sign(s)} and {@code |amount| * n > 3 * |s|}.</li>
 * </ol>
 * The mean is never divided out, so there is no rounding.
 *
 * <p>{@value #MIN_HISTORY} is a fixed MVP constant. There is no configuration property for it.
 */
public final class AbnormalAmountRule implements FraudRule {

    /** Minimum number of category transactions in the window; fixed for the MVP (AC-FRAUD-2c). */
    public static final int MIN_HISTORY = 5;

    /** History window in calendar days, ending today. */
    public static final int WINDOW_DAYS = 30;

    private static final BigDecimal MULTIPLIER = BigDecimal.valueOf(3);

    @Override
    public FraudRuleName name() {
        return FraudRuleName.ABNORMAL_AMOUNT;
    }

    @Override
    public boolean fires(FraudCandidate candidate, FraudHistory history, LocalDateTime now) {
        var today = now.toLocalDate();
        var stats = history.categoryStats(candidate.category(), today.minusDays(WINDOW_DAYS - 1L), today);
        if (stats.count() < MIN_HISTORY) {
            return false;
        }
        var sum = stats.sum();
        if (sum.signum() == 0) {
            return false;
        }
        var amount = candidate.amount();
        if (amount.signum() != sum.signum()) {
            return false;
        }
        var scaledAmount = amount.abs().multiply(BigDecimal.valueOf(stats.count()));
        return scaledAmount.compareTo(MULTIPLIER.multiply(sum.abs())) > 0;
    }
}
