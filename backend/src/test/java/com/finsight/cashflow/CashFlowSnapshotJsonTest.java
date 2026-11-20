package com.finsight.cashflow;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.AccountDto;
import com.finsight.accounts.service.AccountService;
import com.finsight.cashflow.domain.CashFlowSnapshot;
import com.finsight.cashflow.domain.ForecastHorizon;
import com.finsight.cashflow.dto.ForecastDayDto;
import com.finsight.cashflow.repository.CashFlowSnapshotRepository;
import com.finsight.cashflow.service.CashFlowService;
import com.finsight.cashflow.service.RecurringPatternDetector;
import com.finsight.shared.security.RateLimiter;
import com.finsight.transactions.service.TransactionAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stored-snapshot JSON for {@link CashFlowService} (F-8): the start point is written first and read
 * back as {@code startingBalance}; a row without it is recalculated. Clock fixed at 2026-03-15.
 */
@ExtendWith(MockitoExtension.class)
class CashFlowSnapshotJsonTest {

    private static final long CALLER = 41L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 3, 15, 2, 0);

    @Mock private CashFlowSnapshotRepository snapshotRepository;
    @Mock private TransactionAggregationService transactionAggregation;
    @Mock private AccountService accountService;
    @Mock private RateLimiter rateLimiter;

    private CashFlowService service;

    @BeforeEach
    void setUp() {
        service = new CashFlowService(snapshotRepository, transactionAggregation, accountService,
                new RecurringPatternDetector(), rateLimiter,
                Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC), JsonMapper.builder().build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void givenStored(String json) {
        var stored = CashFlowSnapshot.create(CALLER, ForecastHorizon.D30, GENERATED, true, "NO_HISTORY", json);
        when(snapshotRepository.findFirstByUserIdAndHorizonDaysOrderByGeneratedAtDescIdDesc(CALLER, (byte) 30))
                .thenReturn(Optional.of(stored));
    }

    @Test
    @DisplayName("Saved JSON starts with the start point (today, starting balance), then the forecast days")
    void savedJson_startsWithStartPoint() {
        when(rateLimiter.tryConsume("cashflow-refresh:" + CALLER, 1, 3600)).thenReturn(true);
        when(transactionAggregation.amountsBetween(TODAY.minusDays(89), TODAY)).thenReturn(List.of());
        var balance = new BigDecimal("10.00");
        when(accountService.listMine()).thenReturn(List.of(new AccountDto(1L, "A", AccountType.CHECKING,
                balance, balance, "USD", true, LocalDateTime.of(2025, 1, 1, 0, 0))));

        service.refresh();

        var captor = ArgumentCaptor.forClass(CashFlowSnapshot.class);
        verify(snapshotRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().getFirst().getForecastJson()).startsWith(
                "[{\"date\":\"2026-03-15\",\"projected_balance\":10.00},"
                        + "{\"date\":\"2026-03-16\",\"projected_balance\":10.00}");
    }

    @Test
    @DisplayName("A stored snapshot returns its stored starting balance and days, without recalculating")
    void stored_returnsStoredStartingBalance() {
        givenStored("[{\"date\":\"2026-03-15\",\"projected_balance\":70},"
                + "{\"date\":\"2026-03-16\",\"projected_balance\":75.5},"
                + "{\"date\":\"2026-03-17\",\"projected_balance\":80.00}]");

        var result = service.getForecast(null);

        assertThat(result.asOf()).isEqualTo(TODAY);
        assertThat(result.startingBalance()).isEqualTo(new BigDecimal("70.00"));
        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 3, 16));
        assertThat(result.days()).containsExactly(
                new ForecastDayDto(LocalDate.of(2026, 3, 16), new BigDecimal("75.50")),
                new ForecastDayDto(LocalDate.of(2026, 3, 17), new BigDecimal("80.00")));
        assertThat(result.projectedEndBalance()).isEqualTo(new BigDecimal("80.00"));
        assertThat(result.reason()).isEqualTo("NO_HISTORY");
        verifyNoInteractions(transactionAggregation, accountService);
        verify(snapshotRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("A stored row without a start point is recalculated like a missing snapshot")
    void storedWithoutStartPoint_recalculated() {
        givenStored("[{\"date\":\"2026-03-16\",\"projected_balance\":75.5}]");
        when(transactionAggregation.amountsBetween(TODAY.minusDays(89), TODAY)).thenReturn(List.of());
        when(accountService.listMine()).thenReturn(List.of());

        var result = service.getForecast("30");

        assertThat(result.startingBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.days()).hasSize(30);
        verify(snapshotRepository).deleteByUserId(CALLER);
        verify(snapshotRepository, times(3)).save(any(CashFlowSnapshot.class));
    }
}
