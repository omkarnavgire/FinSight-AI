package com.finsight.fraud.domain;

import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;

import java.time.LocalDateTime;

/**
 * One fraud rule (T-49). Rules are stateless and read-only: they only call {@link FraudHistory}
 * and never write (AC-ORD-3).
 */
public sealed interface FraudRule permits DuplicateTransactionRule, AbnormalAmountRule, UnseenCountryRule {

    /** The rule name recorded on an alert. */
    FraudRuleName name();

    /**
     * Evaluates the rule for a transaction that is not inserted yet.
     *
     * @param candidate the new transaction
     * @param history   the user's existing transactions
     * @param now       current time from {@link LocalDateTime#now()}, the same source as
     *                  {@code Transaction.createdAt} (C-1)
     * @return {@code true} if the rule fires
     */
    boolean fires(FraudCandidate candidate, FraudHistory history, LocalDateTime now);
}
