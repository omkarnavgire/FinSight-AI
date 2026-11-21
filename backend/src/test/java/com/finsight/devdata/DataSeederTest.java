package com.finsight.devdata;

import com.finsight.accounts.service.AccountService;
import com.finsight.auth.service.DemoUserSeedService;
import com.finsight.auth.service.UserDirectory;
import com.finsight.budgeting.service.BudgetService;
import com.finsight.cashflow.service.CashFlowService;
import com.finsight.fraud.service.FraudAlertSeedService;
import com.finsight.transactions.service.TransactionSeedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests for {@link DataSeeder} guards and security-context handling (T-61, AC-SEED-1). */
@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    @Mock private UserDirectory userDirectory;
    @Mock private DemoUserSeedService demoUserSeedService;
    @Mock private AccountService accountService;
    @Mock private TransactionSeedService transactionSeedService;
    @Mock private BudgetService budgetService;
    @Mock private FraudAlertSeedService fraudAlertSeedService;
    @Mock private CashFlowService cashFlowService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);

    /** Runs the callback directly; stands in for a real transaction manager. */
    private final TransactionTemplate inline = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    };

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private DataSeeder seeder(String password, String... profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new DataSeeder(userDirectory, demoUserSeedService, accountService, transactionSeedService,
                budgetService, fraudAlertSeedService, cashFlowService, inline, clock, env, password);
    }

    private void verifyNothingSeeded() {
        verifyNoInteractions(demoUserSeedService, accountService, transactionSeedService,
                budgetService, fraudAlertSeedService, cashFlowService);
    }

    @Test
    @DisplayName("Skipped when the prod profile is also active, even with a password set")
    void prodActive_skipped() {
        assertThat(seeder("local-demo-pass", "dev", "prod").seedIfEmpty()).isFalse();
        verifyNothingSeeded();
        verifyNoInteractions(userDirectory);
    }

    @Test
    @DisplayName("Skipped when FINSIGHT_DEMO_PASSWORD is blank")
    void blankPassword_skipped() {
        assertThat(seeder(" ", "dev").seedIfEmpty()).isFalse();
        verifyNothingSeeded();
    }

    @Test
    @DisplayName("Skipped when any user exists (idempotent: a second start changes nothing)")
    void usersExist_skipped() {
        when(userDirectory.userIdsAfter(0, 1)).thenReturn(List.of(7L));

        assertThat(seeder("local-demo-pass", "dev").seedIfEmpty()).isFalse();
        verifyNothingSeeded();
    }

    @Test
    @DisplayName("A failure propagates from seedIfEmpty, and the previous security context is restored")
    void failure_restoresContext() {
        var previous = SecurityContextHolder.createEmptyContext();
        previous.setAuthentication(new UsernamePasswordAuthenticationToken(99L, null, List.of()));
        SecurityContextHolder.setContext(previous);
        when(userDirectory.userIdsAfter(0, 1)).thenReturn(List.of());
        when(demoUserSeedService.createDemoUser(anyString(), anyString(), anyString())).thenReturn(1L);
        when(accountService.create(any())).thenThrow(new IllegalStateException("simulated"));

        assertThatThrownBy(() -> seeder("local-demo-pass", "dev").seedIfEmpty())
                .isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
        verifyNoInteractions(transactionSeedService, fraudAlertSeedService, cashFlowService);
    }

    @Test
    @DisplayName("run() logs a seeding failure instead of stopping dev startup, and clears the context")
    void run_swallowsFailure() {
        when(userDirectory.userIdsAfter(0, 1)).thenReturn(List.of());
        when(demoUserSeedService.createDemoUser(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("simulated"));

        seeder("local-demo-pass", "dev").run(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("The demo password is passed only to the demo-user seed service")
    void password_onlyToUserSeed() {
        when(userDirectory.userIdsAfter(0, 1)).thenReturn(List.of());
        when(demoUserSeedService.createDemoUser(DataSeeder.DEMO_EMAIL, "local-demo-pass", DataSeeder.DEMO_NAME))
                .thenReturn(1L);
        when(accountService.create(any())).thenThrow(new IllegalStateException("stop after user"));

        assertThatThrownBy(() -> seeder("local-demo-pass", "dev").seedIfEmpty())
                .isInstanceOf(IllegalStateException.class);
        verify(demoUserSeedService).createDemoUser(DataSeeder.DEMO_EMAIL, "local-demo-pass", DataSeeder.DEMO_NAME);
    }
}
