package com.finsight.cashflow.scheduler;

import com.finsight.auth.service.UserDirectory;
import com.finsight.cashflow.service.CashFlowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nightly forecast recalculation for every user (T-42, FR-06-3).
 *
 * <h2>Batches</h2>
 * Users are read in keyset batches of {@value #BATCH_SIZE} through {@link UserDirectory}; a batch
 * shorter than {@value #BATCH_SIZE} is the last one.
 *
 * <h2>Identity (D-2)</h2>
 * {@link CashFlowService} reads the user from the security context. For each user this class saves
 * the current context, installs a <em>new</em> context holding only that user's ID, runs the
 * recalculation, and in {@code finally} restores the saved context, or clears it when there was
 * none. Users are processed one at a time on the scheduler thread; no context is shared between
 * users or threads, so one user's identity never reaches the next user's work.
 *
 * <h2>Failure isolation</h2>
 * A failing user is logged (user ID and exception type only) and skipped. Each recalculation is its
 * own transaction, so the failure rolls back only that user's work and their previous snapshots stay.
 */
@Component
public class CashFlowScheduler {

    static final int BATCH_SIZE = 100;
    static final String CRON = "0 0 2 * * *";

    private static final Logger log = LoggerFactory.getLogger(CashFlowScheduler.class);

    private final UserDirectory userDirectory;
    private final CashFlowService cashFlowService;

    public CashFlowScheduler(UserDirectory userDirectory, CashFlowService cashFlowService) {
        this.userDirectory = userDirectory;
        this.cashFlowService = cashFlowService;
    }

    /** Result of one run. */
    public record RunSummary(int succeeded, int failed) {
    }

    /** Runs nightly at 02:00 server time. */
    @Scheduled(cron = CRON)
    public void runNightly() {
        var summary = processAllUsers();
        log.info("Nightly cash-flow recalculation finished: {} succeeded, {} failed",
                summary.succeeded(), summary.failed());
    }

    /**
     * Recalculates forecasts for all users, batch by batch.
     *
     * @return how many users succeeded and failed
     */
    public RunSummary processAllUsers() {
        int succeeded = 0;
        int failed = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = userDirectory.userIdsAfter(lastId, BATCH_SIZE);
            for (var userId : batch) {
                if (recalculateAs(userId)) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();
            }
        } while (batch.size() == BATCH_SIZE);
        return new RunSummary(succeeded, failed);
    }

    /** Runs one user's recalculation under a temporary context for that user only. */
    private boolean recalculateAs(Long userId) {
        var previous = SecurityContextHolder.getContext();
        var hadPrevious = previous.getAuthentication() != null;
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            cashFlowService.recalculateCurrentUser();
            return true;
        } catch (RuntimeException e) {
            log.warn("Cash-flow recalculation failed for user {} ({}); previous snapshot kept",
                    userId, e.getClass().getSimpleName());
            return false;
        } finally {
            if (hadPrevious) {
                SecurityContextHolder.setContext(previous);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
