package com.finsight.cashflow;

import com.finsight.auth.service.UserDirectory;
import com.finsight.cashflow.scheduler.CashFlowScheduler;
import com.finsight.cashflow.service.CashFlowService;
import com.finsight.shared.ownership.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Unit tests for {@link CashFlowScheduler} (T-46, D-2). */
@ExtendWith(MockitoExtension.class)
class CashFlowSchedulerTest {

    @Mock private UserDirectory userDirectory;
    @Mock private CashFlowService cashFlowService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private CashFlowScheduler scheduler() {
        return new CashFlowScheduler(userDirectory, cashFlowService);
    }

    private static List<Long> ids(long fromInclusive, long toInclusive) {
        return LongStream.rangeClosed(fromInclusive, toInclusive).boxed().toList();
    }

    /** Records the user ID the service sees on each call. */
    private List<Long> recordUsersSeen() {
        var seen = new ArrayList<Long>();
        when(cashFlowService.recalculateCurrentUser()).thenAnswer(inv -> {
            seen.add(SecurityUtils.currentUserId());
            return List.of();
        });
        return seen;
    }

    @Test
    @DisplayName("All users are processed across batches of 100, using keyset paging")
    void allUsersProcessed_inBatches() {
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(ids(1, 100));
        when(userDirectory.userIdsAfter(100L, 100)).thenReturn(ids(101, 105));
        var seen = recordUsersSeen();

        var summary = scheduler().processAllUsers();

        assertThat(summary.succeeded()).isEqualTo(105);
        assertThat(summary.failed()).isZero();
        assertThat(seen).containsExactlyElementsOf(ids(1, 105));
        verify(userDirectory, times(2)).userIdsAfter(anyLong(), eq(100));
    }

    @Test
    @DisplayName("A full last batch triggers one more lookup, which returns nothing")
    void exactBatchBoundary() {
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(ids(1, 100));
        when(userDirectory.userIdsAfter(100L, 100)).thenReturn(List.of());
        recordUsersSeen();

        assertThat(scheduler().processAllUsers().succeeded()).isEqualTo(100);
    }

    @Test
    @DisplayName("One user's failure does not abort the batch; later users are still processed")
    void singleFailure_doesNotAbortBatch() {
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(List.of(1L, 2L, 3L));
        var seen = new ArrayList<Long>();
        when(cashFlowService.recalculateCurrentUser()).thenAnswer(inv -> {
            var id = SecurityUtils.currentUserId();
            seen.add(id);
            if (id == 2L) {
                throw new IllegalStateException("simulated");
            }
            return List.of();
        });

        var summary = scheduler().processAllUsers();

        assertThat(seen).containsExactly(1L, 2L, 3L);
        assertThat(summary.succeeded()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("After a failure the next user runs under their own ID, never the failed user's")
    void failure_doesNotLeakIdentity() {
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(List.of(7L, 8L));
        var seen = new ArrayList<Long>();
        when(cashFlowService.recalculateCurrentUser()).thenAnswer(inv -> {
            seen.add(SecurityUtils.currentUserId());
            if (seen.size() == 1) {
                throw new IllegalStateException("simulated");
            }
            return List.of();
        });

        scheduler().processAllUsers();

        assertThat(seen).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("With no previous context, the context is cleared after the run")
    void noPreviousContext_cleared() {
        SecurityContextHolder.clearContext();
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(List.of(5L));
        recordUsersSeen();

        scheduler().processAllUsers();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("With a previous context, that same context is restored after the run, even after a failure")
    void previousContext_restored() {
        var previous = SecurityContextHolder.createEmptyContext();
        var original = new UsernamePasswordAuthenticationToken(999L, null, List.of());
        previous.setAuthentication(original);
        SecurityContextHolder.setContext(previous);
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(List.of(5L, 6L));
        when(cashFlowService.recalculateCurrentUser())
                .thenThrow(new IllegalStateException("simulated"))
                .thenReturn(List.of());

        scheduler().processAllUsers();

        assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(original);
    }

    @Test
    @DisplayName("No users means no recalculation")
    void noUsers() {
        when(userDirectory.userIdsAfter(0L, 100)).thenReturn(List.of());

        var summary = scheduler().processAllUsers();

        assertThat(summary.succeeded()).isZero();
        verifyNoInteractions(cashFlowService);
    }

    @Test
    @DisplayName("The nightly job is scheduled at 02:00 every day")
    void cronIsTwoAm() throws Exception {
        var scheduled = CashFlowScheduler.class.getMethod("runNightly").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 2 * * *");
    }
}
