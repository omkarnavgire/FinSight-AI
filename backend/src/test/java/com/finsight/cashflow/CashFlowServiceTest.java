package com.finsight.cashflow;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.AccountDto;
import com.finsight.accounts.service.AccountService;
import com.finsight.cashflow.domain.CashFlowSnapshot;
import com.finsight.cashflow.domain.ForecastHorizon;
import com.finsight.cashflow.dto.ForecastDayDto;
import com.finsight.cashflow.dto.ForecastDto;
import com.finsight.cashflow.repository.CashFlowSnapshotRepository;
import com.finsight.cashflow.service.CashFlowService;
import com.finsight.cashflow.service.RecurringPatternDetector;
import com.finsight.shared.exception.RateLimitExceededException;
import com.finsight.shared.security.RateLimiter;
import com.finsight.transactions.dto.DatedAmountDto;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests for {@link CashFlowService} (T-45): mocks around a real detector, clock fixed at 2026-03-15. */
@ExtendWith(MockitoExtension.class)
class CashFlowServiceTest {

    private static final long CALLER = 31L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final LocalDate WINDOW_START = LocalDate.of(2025, 12, 16); // today - 89

    @Mock private CashFlowSnapshotRepository snapshotRepository;
    @Mock private TransactionAggregationService transactionAggregation;
    @Mock private AccountService accountService;
    @Mock private RateLimiter rateLimiter;

    private CashFlowService service;

    @BeforeEach
    void setUp() {
        service = serviceAt("2026-03-15T10:00:00Z");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private CashFlowService serviceAt(String instant) {
        return new CashFlowService(snapshotRepository, transactionAggregation, accountService,
                new RecurringPatternDetector(), rateLimiter,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), JsonMapper.builder().build());
    }

    private static AccountDto account(boolean active, String balance) {
        var b = new BigDecimal(balance);
        return new AccountDto(1L, "A", AccountType.CHECKING, b, b, "USD", active, LocalDateTime.of(2025, 1, 1, 0, 0));
    }

    private static DatedAmountDto tx(LocalDate date, String amount) {
        return new DatedAmountDto(date, new BigDecimal(amount));
    }

    private void givenHistory(List<DatedAmountDto> history) {
        when(transactionAggregation.amountsBetween(WINDOW_START, TODAY)).thenReturn(history);
    }

    private void givenBalance(String balance) {
        when(accountService.listMine()).thenReturn(List.of(account(true, balance)));
    }

    private void givenRefreshAllowed() {
        when(rateLimiter.tryConsume("cashflow-refresh:" + CALLER, 1, 3600)).thenReturn(true);
    }

    private List<CashFlowSnapshot> savedSnapshots() {
        var captor = ArgumentCaptor.forClass(CashFlowSnapshot.class);
        verify(snapshotRepository, times(3)).save(captor.capture());
        return captor.getAllValues();
    }

    // ---- confidence (D-5) ----

    @Test
    @DisplayName("29 elapsed days since the oldest transaction is low confidence, without a reason")
    void history29Days_lowConfidence() {
        givenRefreshAllowed();
        givenHistory(List.of(tx(TODAY.minusDays(29), "-10.00")));
        givenBalance("500.00");

        var result = service.refresh();

        assertThat(result).allSatisfy(f -> {
            assertThat(f.lowConfidence()).isTrue();
            assertThat(f.reason()).isNull();
        });
    }

    @Test
    @DisplayName("Exactly 30 elapsed days since the oldest transaction is not low confidence")
    void history30Days_notLowConfidence() {
        givenRefreshAllowed();
        givenHistory(List.of(tx(TODAY.minusDays(30), "-10.00")));
        givenBalance("500.00");

        var result = service.refresh();

        assertThat(result).extracting(ForecastDto::lowConfidence).containsOnly(false);
    }

    @Test
    @DisplayName("Zero history: flat forecast, lowConfidence=true, reason=NO_HISTORY, one point per day")
    void zeroHistory_shape() {
        givenRefreshAllowed();
        givenHistory(List.of());
        givenBalance("1234.50");

        var thirty = service.refresh().getFirst();

        assertThat(thirty.horizonDays()).isEqualTo(30);
        assertThat(thirty.asOf()).isEqualTo(TODAY);
        assertThat(thirty.startingBalance()).isEqualTo(new BigDecimal("1234.50"));
        assertThat(thirty.lowConfidence()).isTrue();
        assertThat(thirty.reason()).isEqualTo("NO_HISTORY");
        assertThat(thirty.from()).isEqualTo(LocalDate.of(2026, 3, 16));
        assertThat(thirty.to()).isEqualTo(LocalDate.of(2026, 4, 14));
        assertThat(thirty.days()).hasSize(30)
                .extracting(ForecastDayDto::projectedBalance).containsOnly(new BigDecimal("1234.50"));
        assertThat(thirty.projectedEndBalance()).isEqualTo(new BigDecimal("1234.50"));
    }

    // ---- recalculation ----

    @Test
    @DisplayName("Recalculation builds and saves all three horizons: 30, 60 and 90 days")
    void recalculation_buildsThreeHorizons() {
        givenRefreshAllowed();
        givenHistory(List.of());
        givenBalance("100.00");

        var result = service.refresh();

        assertThat(result).extracting(ForecastDto::horizonDays).containsExactly(30, 60, 90);
        assertThat(result).extracting(f -> f.days().size()).containsExactly(30, 60, 90);
        assertThat(savedSnapshots()).extracting(CashFlowSnapshot::getHorizon)
                .containsExactly(ForecastHorizon.D30, ForecastHorizon.D60, ForecastHorizon.D90);
    }

    @Test
    @DisplayName("Old snapshots are deleted only after all forecasts were built, then the new ones are saved")
    void replacement_orderReadThenDeleteThenSave() {
        givenRefreshAllowed();
        givenHistory(List.of());
        givenBalance("100.00");

        service.refresh();

        var order = inOrder(transactionAggregation, accountService, snapshotRepository);
        order.verify(transactionAggregation).amountsBetween(WINDOW_START, TODAY);
        order.verify(accountService).listMine();
        order.verify(snapshotRepository).deleteByUserId(CALLER);
        order.verify(snapshotRepository, times(3)).save(any(CashFlowSnapshot.class));
    }

    @Test
    @DisplayName("A failure while building deletes and saves nothing, so the previous snapshots stay")
    void failure_preservesPreviousSnapshot() {
        givenRefreshAllowed();
        when(transactionAggregation.amountsBetween(WINDOW_START, TODAY))
                .thenThrow(new IllegalStateException("simulated"));

        assertThatThrownBy(() -> service.refresh()).isInstanceOf(IllegalStateException.class);

        verify(snapshotRepository, never()).deleteByUserId(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("Only active accounts count towards the starting balance")
    void startingBalance_activeOnly() {
        givenRefreshAllowed();
        givenHistory(List.of());
        when(accountService.listMine()).thenReturn(List.of(
                account(true, "300.00"), account(false, "9999.00"), account(true, "200.25")));

        var thirty = service.refresh().getFirst();
        assertThat(thirty.startingBalance()).isEqualTo(new BigDecimal("500.25"));
        assertThat(thirty.projectedEndBalance()).isEqualTo(new BigDecimal("500.25"));
    }

    @Test
    @DisplayName("A recurring item on day 31 lands on 31 January and on 28 February")
    void recurringDay31_shortMonth() {
        service = serviceAt("2026-01-20T10:00:00Z");
        var today = LocalDate.of(2026, 1, 20);
        when(rateLimiter.tryConsume("cashflow-refresh:" + CALLER, 1, 3600)).thenReturn(true);
        when(transactionAggregation.amountsBetween(today.minusDays(89), today)).thenReturn(List.of(
                tx(LocalDate.of(2025, 10, 31), "-100.00"), tx(LocalDate.of(2025, 12, 31), "-100.00")));
        givenBalance("1000.00");

        var sixty = service.refresh().get(1);

        assertThat(balanceOn(sixty, "2026-01-30")).isEqualTo(new BigDecimal("1000.00"));
        assertThat(balanceOn(sixty, "2026-01-31")).isEqualTo(new BigDecimal("900.00"));
        assertThat(balanceOn(sixty, "2026-02-27")).isEqualTo(new BigDecimal("900.00"));
        assertThat(balanceOn(sixty, "2026-02-28")).isEqualTo(new BigDecimal("800.00"));
        assertThat(sixty.projectedEndBalance()).isEqualTo(new BigDecimal("800.00"));
        assertThat(sixty.lowConfidence()).isFalse();
    }

    private static BigDecimal balanceOn(ForecastDto forecast, String date) {
        return forecast.days().stream().filter(d -> d.date().equals(LocalDate.parse(date)))
                .findFirst().orElseThrow().projectedBalance();
    }

    // ---- refresh rate limit (D-12) ----

    @Test
    @DisplayName("A refresh over the hourly limit is 429 with the limiter's retry time and recalculates nothing")
    void refresh_overLimit_429() {
        when(rateLimiter.tryConsume("cashflow-refresh:" + CALLER, 1, 3600)).thenReturn(false);
        when(rateLimiter.retryAfterSeconds("cashflow-refresh:" + CALLER, 3600)).thenReturn(1200L);

        assertThatThrownBy(() -> service.refresh())
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1200L));
        verifyNoInteractions(transactionAggregation, accountService, snapshotRepository);
    }

    // ---- GET (D-8) ----

    @Test
    @DisplayName("GET without a stored snapshot builds and saves all three, then returns the requested one")
    void get_noSnapshot_buildsAll() {
        when(snapshotRepository.findFirstByUserIdAndHorizonDaysOrderByGeneratedAtDescIdDesc(CALLER, (byte) 60))
                .thenReturn(Optional.empty());
        givenHistory(List.of());
        givenBalance("50.00");

        var result = service.getForecast("60");

        assertThat(result.horizonDays()).isEqualTo(60);
        assertThat(result.days()).hasSize(60);
        assertThat(savedSnapshots()).hasSize(3);
        verifyNoInteractions(rateLimiter);
    }
}
