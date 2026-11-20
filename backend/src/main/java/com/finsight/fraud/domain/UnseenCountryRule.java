package com.finsight.fraud.domain;

import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;

import java.time.LocalDateTime;

/**
 * Rule 3, unseen country (FR-07-3, AC-FRAUD-4, Q-6).
 *
 * <p>Skipped on the user's first-ever transaction. Otherwise fires when no earlier transaction of
 * the user, on any account (deactivated ones included) and at any time, used the same
 * {@code countryCode}. The code is client-supplied metadata: a behavioural signal, not proof of
 * location.
 */
public final class UnseenCountryRule implements FraudRule {

    @Override
    public FraudRuleName name() {
        return FraudRuleName.UNSEEN_COUNTRY;
    }

    @Override
    public boolean fires(FraudCandidate candidate, FraudHistory history, LocalDateTime now) {
        if (!history.hasAnyTransaction()) {
            return false;
        }
        return !history.hasUsedCountry(candidate.countryCode());
    }
}
