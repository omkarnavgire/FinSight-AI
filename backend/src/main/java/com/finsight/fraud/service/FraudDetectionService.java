package com.finsight.fraud.service;

import com.finsight.fraud.domain.AbnormalAmountRule;
import com.finsight.fraud.domain.DuplicateTransactionRule;
import com.finsight.fraud.domain.FraudAlert;
import com.finsight.fraud.domain.FraudRule;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.domain.UnseenCountryRule;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.repository.FraudAlertRepository;
import com.finsight.shared.ownership.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Runs the fraud rules and records fired rules as alerts (T-52).
 *
 * <h2>Where it runs</h2>
 * Called only from {@code TransactionService.create()}: {@link #evaluate} before the transaction
 * INSERT, {@link #recordAlerts} after the balance update. Both are {@code MANDATORY}, so they always
 * join that one transaction (C-3). Neither is read-only, opens a new transaction or suppresses a
 * rollback, and no exception is caught here: a failing alert INSERT rolls back the transaction row,
 * the balance change and every alert of that call (AC-ORD-2).
 *
 * <h2>Rules</h2>
 * All rules are evaluated in a fixed order on every call. Rules only read through
 * {@link FraudHistory} (AC-ORD-3). "Now" is {@link LocalDateTime#now()}, the same source as
 * {@code Transaction.createdAt}; the application {@code Clock} is deliberately not used (C-1).
 *
 * <h2>Ownership</h2>
 * The alert owner always comes from {@link SecurityUtils#currentUserId()}.
 */
@Service
public class FraudDetectionService {

    /** Every rule, in evaluation order. The rules hold no state. */
    static final List<FraudRule> RULES = List.of(
            new DuplicateTransactionRule(), new AbnormalAmountRule(), new UnseenCountryRule());

    private final FraudHistory history;
    private final FraudAlertRepository alertRepository;

    public FraudDetectionService(FraudHistory history, FraudAlertRepository alertRepository) {
        this.history = history;
        this.alertRepository = alertRepository;
    }

    /**
     * Evaluates all rules for a transaction that is not inserted yet. Read-only: nothing is written.
     *
     * @param candidate the new transaction
     * @return the fired rules, in evaluation order; empty if none fired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<FraudRuleName> evaluate(FraudCandidate candidate) {
        var now = LocalDateTime.now();
        return RULES.stream()
                .filter(rule -> rule.fires(candidate, history, now))
                .map(FraudRule::name)
                .toList();
    }

    /**
     * Inserts one alert per fired rule for an already-inserted transaction, in the caller's
     * transaction. Does nothing when {@code fired} is empty.
     *
     * @param transactionId the inserted transaction
     * @param fired         rules returned by {@link #evaluate}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAlerts(Long transactionId, List<FraudRuleName> fired) {
        if (fired.isEmpty()) {
            return;
        }
        var userId = SecurityUtils.currentUserId();
        // Whole seconds, matching the DATETIME column, so the stored value equals this one.
        var now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        for (var rule : fired) {
            alertRepository.save(FraudAlert.create(userId, transactionId, rule, now));
        }
    }
}
