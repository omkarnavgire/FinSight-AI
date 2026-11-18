package com.finsight.budgeting.service;

import com.finsight.budgeting.event.BudgetChangedEvent;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.transactions.event.TransactionsChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers budget-alert evaluation after a committed change (E-1 and E-2 in the Phase 6 plan).
 *
 * <h2>Never breaks a committed transaction</h2>
 * Runs only after the source transaction committed ({@code AFTER_COMMIT}); nothing runs after a
 * rollback. This class is deliberately <em>not</em> {@code @Transactional}: evaluation happens in
 * {@link BudgetAlertService}'s own {@code REQUIRES_NEW} transaction, and every exception — including a
 * failed commit of that new transaction — is caught here. An exception thrown from an after-commit
 * callback would otherwise reach the HTTP caller although the data was already committed.
 *
 * <h2>Recovery</h2>
 * A lock failure (deadlock or lock-wait timeout) is retried once. Any other failure is logged; the
 * evaluation is idempotent and is repeated by the next change, budget edit or progress read (E-3).
 *
 * <h2>Synchronous only</h2>
 * Must run in the request thread (never {@code @Async}): evaluation reads the user from the security
 * context. As a guard, an event whose user differs from the context user is skipped.
 */
@Component
public class BudgetAlertListener {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertListener.class);

    private final BudgetAlertService alertService;

    public BudgetAlertListener(BudgetAlertService alertService) {
        this.alertService = alertService;
    }

    /** Evaluates the budgets for every changed (category, month) after a transaction change commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionsChanged(TransactionsChangedEvent event) {
        runSafely(event.userId(), "transactions changed",
                () -> alertService.evaluateAffectedInNewTransaction(event.affected()));
    }

    /** Evaluates a budget after it was created or its limit changed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBudgetChanged(BudgetChangedEvent event) {
        runSafely(event.userId(), "budget " + event.budgetId() + " changed",
                () -> alertService.evaluateBudgetInNewTransaction(event.budgetId()));
    }

    private void runSafely(Long eventUserId, String trigger, Runnable evaluation) {
        try {
            if (!eventUserId.equals(SecurityUtils.currentUserId())) {
                log.warn("Budget alert evaluation skipped ({}): event user does not match the security context", trigger);
                return;
            }
            try {
                evaluation.run();
            } catch (PessimisticLockingFailureException lockFailure) {
                log.info("Budget alert evaluation hit a lock conflict ({}); retrying once", trigger);
                evaluation.run();
            }
        } catch (RuntimeException e) {
            log.warn("Budget alert evaluation failed ({}) with {}; it is repeated on the next change, "
                    + "budget edit or progress read", trigger, e.getClass().getSimpleName());
            log.debug("Budget alert evaluation failure detail", e);
        }
    }
}
