package com.finsight.cashflow.service;

import com.finsight.transactions.dto.DatedAmountDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Finds recurring income and expenses in a list of transactions (T-40, FR-06-2, D-6).
 *
 * <h2>Rule</h2>
 * Transactions form one recurring item when all of these hold for the whole group:
 * <ul>
 *   <li>same sign (credits and debits are never mixed);</li>
 *   <li>amounts match: {@code max(|amount|) <= min(|amount|) * 1.02}, so exactly 2 % matches;</li>
 *   <li>days of month match: {@code max(day) - min(day) <= 2} (no wrap from the 31st to the 1st);</li>
 *   <li>the group falls in at least two different calendar months.</li>
 * </ul>
 *
 * <h2>Grouping</h2>
 * Transactions are taken oldest first and each joins the first existing group it fits, otherwise it
 * starts a new group. Each transaction belongs to at most one group, so the result is deterministic.
 *
 * <h2>Result</h2>
 * One {@link RecurringItem} per qualifying group: the signed mean amount (2 decimals, half-up) and
 * the day of month of the group's most recent transaction. Ordered by day, then amount.
 */
@Component
public class RecurringPatternDetector {

    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.02");
    static final int DAY_TOLERANCE = 2;
    static final int MIN_DISTINCT_MONTHS = 2;

    /**
     * A recurring credit (positive) or debit (negative), projected once per month.
     *
     * @param dayOfMonth day the item is expected, 1–31
     * @param amount     signed amount, 2 decimals
     */
    public record RecurringItem(int dayOfMonth, BigDecimal amount) {
    }

    /**
     * Detects recurring items.
     *
     * @param transactions any order; zero amounts are ignored; may be empty
     * @return recurring items; empty when none qualify
     */
    public List<RecurringItem> detect(List<DatedAmountDto> transactions) {
        var ordered = transactions.stream()
                .filter(t -> t.amount().signum() != 0)
                .sorted(Comparator.comparing(DatedAmountDto::date).thenComparing(DatedAmountDto::amount))
                .toList();

        var groups = new ArrayList<Group>();
        for (var t : ordered) {
            groups.stream().filter(g -> g.accepts(t)).findFirst()
                    .ifPresentOrElse(g -> g.add(t), () -> groups.add(new Group(t)));
        }

        return groups.stream()
                .filter(Group::spansEnoughMonths)
                .map(Group::toItem)
                .sorted(Comparator.comparingInt(RecurringItem::dayOfMonth).thenComparing(RecurringItem::amount))
                .toList();
    }

    /** Mutable working set used only inside {@link #detect}. */
    private static final class Group {
        private final List<DatedAmountDto> members = new ArrayList<>();
        private final int sign;
        private BigDecimal minAbs;
        private BigDecimal maxAbs;
        private int minDay;
        private int maxDay;

        Group(DatedAmountDto first) {
            sign = first.amount().signum();
            minAbs = first.amount().abs();
            maxAbs = minAbs;
            minDay = first.date().getDayOfMonth();
            maxDay = minDay;
            members.add(first);
        }

        boolean accepts(DatedAmountDto t) {
            if (t.amount().signum() != sign) {
                return false;
            }
            var abs = t.amount().abs();
            var newMin = abs.min(minAbs);
            var newMax = abs.max(maxAbs);
            if (newMax.compareTo(newMin.multiply(AMOUNT_TOLERANCE)) > 0) {
                return false;
            }
            var day = t.date().getDayOfMonth();
            return Math.max(day, maxDay) - Math.min(day, minDay) <= DAY_TOLERANCE;
        }

        void add(DatedAmountDto t) {
            var abs = t.amount().abs();
            minAbs = abs.min(minAbs);
            maxAbs = abs.max(maxAbs);
            var day = t.date().getDayOfMonth();
            minDay = Math.min(day, minDay);
            maxDay = Math.max(day, maxDay);
            members.add(t);
        }

        boolean spansEnoughMonths() {
            var months = new HashSet<YearMonth>();
            members.forEach(m -> months.add(YearMonth.from(m.date())));
            return months.size() >= MIN_DISTINCT_MONTHS;
        }

        RecurringItem toItem() {
            var sum = members.stream().map(DatedAmountDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var mean = sum.divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);
            var latest = members.getLast().date().getDayOfMonth();
            return new RecurringItem(latest, mean);
        }
    }
}
