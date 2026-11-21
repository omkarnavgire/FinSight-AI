package com.finsight.shared;

import com.finsight.accounts.repository.AccountRepository;
import com.finsight.accounts.service.AccountService;
import com.finsight.budgeting.repository.BudgetAlertRepository;
import com.finsight.budgeting.repository.BudgetRepository;
import com.finsight.budgeting.service.BudgetAlertService;
import com.finsight.budgeting.service.BudgetService;
import com.finsight.budgeting.service.SpendingCalculator;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.pagination.PageParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Unit tests for paged account and budget lists (T-60, Q-5): owner scope, default and maximum size. */
@ExtendWith(MockitoExtension.class)
class PagingServicesTest {

    private static final long CALLER = 81L;

    @Mock private AccountRepository accountRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private BudgetAlertRepository alertRepository;
    @Mock private BudgetAlertService alertService;
    @Mock private SpendingCalculator spendingCalculator;
    @Mock private ApplicationEventPublisher events;

    private AccountService accountService;
    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
        budgetService = new BudgetService(budgetRepository, alertRepository, alertService, spendingCalculator, events,
                Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PageParams: default 20, max 100, negative page or size below 1 is 400")
    void pageParams() {
        assertThat(PageParams.DEFAULT_SIZE).isEqualTo(20);
        assertThat(PageParams.of(2, 500)).isEqualTo(PageRequest.of(2, 100));
        assertThat(PageParams.of(0, 20)).isEqualTo(PageRequest.of(0, 20));
        assertThatThrownBy(() -> PageParams.of(-1, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageParams.of(0, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("Account page is scoped to the caller and capped at 100")
    void accounts_ownerScopedAndCapped() {
        when(accountRepository.findPageByUserIdOrderByCreatedAtAscIdAsc(CALLER, PageRequest.of(0, 100)))
                .thenReturn(Page.empty(PageRequest.of(0, 100)));

        var page = accountService.listMinePage(0, 1000);

        assertThat(page.size()).isEqualTo(100);
        verify(accountRepository).findPageByUserIdOrderByCreatedAtAscIdAsc(CALLER, PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("Budget page is scoped to the caller and the current period from the clock")
    void budgets_ownerAndPeriodScoped() {
        when(budgetRepository.findPageByUserIdAndPeriodYearAndPeriodMonthOrderByCategoryAscIdAsc(
                CALLER, (short) 2026, (byte) 7, PageRequest.of(1, 20)))
                .thenReturn(Page.empty(PageRequest.of(1, 20)));

        var page = budgetService.listCurrentPeriodPage(1, 20);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.content()).isEmpty();
    }

    @Test
    @DisplayName("Invalid paging is 400 and runs no query")
    void invalidPaging_noQuery() {
        assertThatThrownBy(() -> accountService.listMinePage(-1, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> budgetService.listCurrentPeriodPage(0, 0)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(accountRepository, budgetRepository);
    }
}
