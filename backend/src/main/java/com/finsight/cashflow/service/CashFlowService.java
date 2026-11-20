package com.finsight.cashflow.service;

import com.finsight.accounts.dto.AccountDto;
import com.finsight.accounts.service.AccountService;
import com.finsight.cashflow.domain.CashFlowSnapshot;
import com.finsight.cashflow.domain.ForecastHorizon;
import com.finsight.cashflow.dto.ForecastDayDto;
import com.finsight.cashflow.dto.ForecastDto;
import com.finsight.cashflow.repository.CashFlowSnapshotRepository;
import com.finsight.cashflow.service.RecurringPatternDetector.RecurringItem;
import com.finsight.shared.exception.RateLimitExceededException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.shared.security.RateLimiter;
import com.finsight.transactions.dto.DatedAmountDto;
import com.finsight.transactions.service.TransactionAggregationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cash-flow forecasts (T-41, FR-06).
 *
 * <h2>Ownership</h2>
 * The user always comes from {@link SecurityUtils#currentUserId()}; no public method accepts a
 * user ID. The scheduler runs each user under a temporary security context for that user.
 *
 * <h2>Model</h2>
 * Starting balance = sum of the user's <em>active</em> account balances (D-4). Recurring items
 * are detected in the last {@value #HISTORY_DAYS} days ({@code today-89 … today}) and projected
 * on their day of month (D-6, D-7). There is one point per day from {@code today+1} to
 * {@code today+horizon}. A day of month the month does not have moves to the month's last day.
 *
 * <h2>Confidence</h2>
 * No transactions in the window: flat forecast, {@code lowConfidence=true},
 * {@code reason="NO_HISTORY"}. Otherwise {@code lowConfidence = DAYS.between(oldest, today) < 30}:
 * exactly 30 elapsed days is not low confidence (D-5).
 *
 * <h2>Replacement (D-9)</h2>
 * All three horizons are built in memory first. Only then, in the same transaction, the user's
 * old snapshots are deleted and the new ones inserted. Any failure rolls the transaction back, so
 * the previous snapshots stay.
 */
@Service
public class CashFlowService {

    static final int HISTORY_DAYS = 90;
    static final int MIN_CONFIDENT_HISTORY_DAYS = 30;
    static final String NO_HISTORY = "NO_HISTORY";
    static final String REFRESH_KEY_PREFIX = "cashflow-refresh:";
    static final int REFRESH_MAX_ATTEMPTS = 1;
    static final long REFRESH_WINDOW_SECONDS = 3600;

    private static final String JSON_DATE = "date";
    private static final String JSON_BALANCE = "projected_balance";

    private final CashFlowSnapshotRepository snapshotRepository;
    private final TransactionAggregationService transactionAggregation;
    private final AccountService accountService;
    private final RecurringPatternDetector detector;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public CashFlowService(CashFlowSnapshotRepository snapshotRepository,
                           TransactionAggregationService transactionAggregation,
                           AccountService accountService,
                           RecurringPatternDetector detector,
                           RateLimiter rateLimiter,
                           Clock clock,
                           ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.transactionAggregation = transactionAggregation;
        this.accountService = accountService;
        this.detector = detector;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * The caller's stored forecast for one horizon. With no stored snapshot, all three horizons are
     * calculated and saved first (D-8).
     *
     * @param horizon {@code "30"}, {@code "60"} or {@code "90"}; absent means 30
     * @return the forecast
     * @throws com.finsight.shared.exception.BadRequestException for any other horizon (400)
     */
    @Transactional
    public ForecastDto getForecast(String horizon) {
        var requested = ForecastHorizon.parse(horizon);
        var userId = SecurityUtils.currentUserId();
        return snapshotRepository
                .findFirstByUserIdAndHorizonDaysOrderByGeneratedAtDescIdDesc(userId, (byte) requested.days())
                .flatMap(this::toDto)
                .orElseGet(() -> recalculate(userId).get(requested.ordinal()));
    }

    /**
     * Recalculates all three horizons now (FR-06-7). Limited to one call per hour per user (D-12).
     *
     * <p><b>Known limitation (F-4, accepted):</b> the hourly slot is consumed before the work runs.
     * A refresh that then fails (and rolls back, keeping the previous snapshots) still uses the
     * slot, so the user must wait for {@code Retry-After} before trying again. This keeps a failing
     * calculation from being retried in a tight loop.
     *
     * @return the 30-, 60- and 90-day forecasts, in that order
     * @throws RateLimitExceededException on a second call within the hour (429 + {@code Retry-After})
     */
    @Transactional
    public List<ForecastDto> refresh() {
        var userId = SecurityUtils.currentUserId();
        var key = REFRESH_KEY_PREFIX + userId;
        if (!rateLimiter.tryConsume(key, REFRESH_MAX_ATTEMPTS, REFRESH_WINDOW_SECONDS)) {
            throw new RateLimitExceededException(rateLimiter.retryAfterSeconds(key, REFRESH_WINDOW_SECONDS));
        }
        return recalculate(userId);
    }

    /**
     * Recalculates all three horizons for the current security-context user, without a rate limit.
     * Used by {@code CashFlowScheduler}, which sets the context per user.
     *
     * @return the 30-, 60- and 90-day forecasts, in that order
     */
    @Transactional
    public List<ForecastDto> recalculateCurrentUser() {
        return recalculate(SecurityUtils.currentUserId());
    }

    // ---- calculation ----

    /** Builds all horizons, then replaces the stored snapshots (D-9). Call inside a transaction. */
    private List<ForecastDto> recalculate(Long userId) {
        var today = LocalDate.now(clock);
        var generatedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        var history = transactionAggregation.amountsBetween(today.minusDays(HISTORY_DAYS - 1L), today);
        var start = startingBalance();

        var noHistory = history.isEmpty();
        var lowConfidence = noHistory || isShortHistory(history, today);
        var reason = noHistory ? NO_HISTORY : null;
        List<RecurringItem> items = noHistory ? List.of() : detector.detect(history);

        // Step 1: build everything. Nothing is written if any of this fails.
        var forecasts = new ArrayList<ForecastDto>();
        var snapshots = new ArrayList<CashFlowSnapshot>();
        for (var horizon : ForecastHorizon.values()) {
            var days = project(start, items, today, horizon.days());
            forecasts.add(forecast(horizon, today, start, days, lowConfidence, reason, generatedAt));
            snapshots.add(CashFlowSnapshot.create(userId, horizon, generatedAt, lowConfidence, reason,
                    toJson(today, start, days)));
        }

        // Step 2: replace, in this transaction.
        snapshotRepository.deleteByUserId(userId);
        snapshots.forEach(snapshotRepository::save);
        return List.copyOf(forecasts);
    }

    private static boolean isShortHistory(List<DatedAmountDto> history, LocalDate today) {
        var oldest = history.stream().map(DatedAmountDto::date).min(LocalDate::compareTo).orElseThrow();
        return ChronoUnit.DAYS.between(oldest, today) < MIN_CONFIDENT_HISTORY_DAYS;
    }

    private BigDecimal startingBalance() {
        return money(accountService.listMine().stream()
                .filter(AccountDto::active)
                .map(AccountDto::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** One point per day from {@code today+1} to {@code today+horizonDays}. */
    static List<ForecastDayDto> project(BigDecimal start, List<RecurringItem> items, LocalDate today, int horizonDays) {
        var balance = money(start);
        var days = new ArrayList<ForecastDayDto>(horizonDays);
        for (int i = 1; i <= horizonDays; i++) {
            var day = today.plusDays(i);
            for (var item : items) {
                if (day.getDayOfMonth() == Math.min(item.dayOfMonth(), day.lengthOfMonth())) {
                    balance = balance.add(item.amount());
                }
            }
            days.add(new ForecastDayDto(day, money(balance)));
        }
        return days;
    }

    // ---- snapshot JSON (F-8) ----

    /**
     * Writes {@code [{"date":"YYYY-MM-DD","projected_balance":n}, ...]} as the V6 schema documents.
     * The first element is the start point: {@code asOf} with the starting balance. It is stored in
     * the same row as the projection, so a stored forecast always returns the balance it was built from.
     */
    private String toJson(LocalDate asOf, BigDecimal start, List<ForecastDayDto> days) {
        var rows = new ArrayList<Map<String, Object>>(days.size() + 1);
        rows.add(jsonRow(asOf, start));
        days.forEach(d -> rows.add(jsonRow(d.date(), d.projectedBalance())));
        return objectMapper.writeValueAsString(rows);
    }

    private static Map<String, Object> jsonRow(LocalDate date, BigDecimal balance) {
        var row = new LinkedHashMap<String, Object>();
        row.put(JSON_DATE, date.toString());
        row.put(JSON_BALANCE, money(balance));
        return row;
    }

    /**
     * Reads a stored snapshot. Empty when the row has no start point (first date is not the
     * calculation date) or no forecast days; the caller then recalculates, as for a missing snapshot.
     */
    private Optional<ForecastDto> toDto(CashFlowSnapshot snapshot) {
        JsonNode root = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(snapshot.getForecastJson());
        var points = new ArrayList<ForecastDayDto>(root.size());
        for (int i = 0; i < root.size(); i++) {
            var node = root.get(i);
            points.add(new ForecastDayDto(LocalDate.parse(node.get(JSON_DATE).asString()),
                    money(node.get(JSON_BALANCE).decimalValue())));
        }
        var asOf = snapshot.getGeneratedAt().toLocalDate();
        if (points.size() < 2 || !points.getFirst().date().equals(asOf)) {
            return Optional.empty();
        }
        var start = points.getFirst().projectedBalance();
        return Optional.of(forecast(snapshot.getHorizon(), asOf, start, points.subList(1, points.size()),
                snapshot.isLowConfidence(), snapshot.getConfidenceReason(), snapshot.getGeneratedAt()));
    }

    private static ForecastDto forecast(ForecastHorizon horizon, LocalDate asOf, BigDecimal start,
                                        List<ForecastDayDto> days, boolean lowConfidence, String reason,
                                        LocalDateTime generatedAt) {
        return new ForecastDto(horizon.days(), asOf, days.getFirst().date(), days.getLast().date(),
                money(start), days.getLast().projectedBalance(), lowConfidence, reason, generatedAt,
                List.copyOf(days));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
