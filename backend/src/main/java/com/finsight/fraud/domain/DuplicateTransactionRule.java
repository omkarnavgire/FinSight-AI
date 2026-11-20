package com.finsight.fraud.domain;

import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;

import java.time.LocalDateTime;

/**
 * Rule 1, duplicate transaction (FR-07-1, AC-FRAUD-1, Q-3, Q-4, C-1).
 *
 * <p>Fires when an existing transaction on the same account has the same amount and the same
 * description ({@code null} matches {@code null}) and was created at or after
 * {@code since = now - }{@value #WINDOW_SECONDS}{@code  s}. Exactly 60 seconds still fires.
 * With no prior matching transaction the rule does not fire.
 *
 * <p>{@code since} is computed here in Java from {@link LocalDateTime#now()}; no database clock
 * ({@code NOW()}, {@code CURRENT_TIMESTAMP}) is used.
 */
public final class DuplicateTransactionRule implements FraudRule {

    /** Duplicate window in seconds. */
    public static final int WINDOW_SECONDS = 60;

    @Override
    public FraudRuleName name() {
        return FraudRuleName.DUPLICATE_TRANSACTION;
    }

    @Override
    public boolean fires(FraudCandidate candidate, FraudHistory history, LocalDateTime now) {
        var since = now.minusSeconds(WINDOW_SECONDS);
        return history.latestDuplicateCreatedAt(candidate.accountId(), candidate.amount(), candidate.description())
                .map(createdAt -> !createdAt.isBefore(since))
                .orElse(false);
    }
}
