package com.finsight.devdata;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The 180 synthetic demo transactions (FR-02-4, design "Synthetic Data Seed"). Pure and repeatable:
 * the same {@code today} always gives the same list (fixed random seed {@value #RANDOM_SEED}).
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>All dates in {@code today-89 … today}; every category appears; country codes US, GB, CA only.</li>
 *   <li>3 monthly salaries (INCOME) and 3 monthly rent payments (UTILITIES) on the checking account.</li>
 *   <li>6 ordinary DINING rows in the last 30 days, so the abnormal-amount rule has history.</li>
 *   <li>The last row is the planned abnormal DINING payment ({@link #abnormalIndex()}): far above
 *       3 x the 30-day DINING mean, so the single seeded ABNORMAL_AMOUNT alert is consistent with
 *       the rule.</li>
 * </ul>
 */
public final class SyntheticDataPlan {

    static final long RANDOM_SEED = 42L;
    static final int TOTAL = 180;
    static final int HISTORY_DAYS = 90;
    static final BigDecimal ABNORMAL_AMOUNT = new BigDecimal("-600.00");

    private static final Category[] EXPENSES = {
            Category.GROCERIES, Category.TRANSPORT, Category.UTILITIES,
            Category.DINING, Category.ENTERTAINMENT, Category.OTHER};

    private final List<CreateTransactionRequest> transactions;

    private SyntheticDataPlan(List<CreateTransactionRequest> transactions) {
        this.transactions = List.copyOf(transactions);
    }

    /** Builds the plan for two accounts, relative to {@code today}. */
    public static SyntheticDataPlan build(LocalDate today, long checkingId, long savingsId) {
        var random = new Random(RANDOM_SEED);
        var rows = new ArrayList<CreateTransactionRequest>(TOTAL);
        var first = today.minusDays(HISTORY_DAYS - 1L);

        for (int m = 0; m < 3; m++) {
            rows.add(row(checkingId, "3000.00", first.plusDays(4 + 30L * m), Category.INCOME, "Salary", "US"));
            rows.add(row(checkingId, "-1200.00", first.plusDays(5 + 30L * m), Category.UTILITIES, "Rent", "US"));
        }
        for (int i = 0; i < 6; i++) {
            rows.add(row(checkingId, money(-(20 + random.nextInt(16)) - random.nextInt(100) / 100.0),
                    today.minusDays(2 + 4L * i), Category.DINING, "Cafe " + (i + 1), "US"));
        }
        int randomRows = TOTAL - rows.size() - 1;
        for (int i = 0; i < randomRows; i++) {
            var category = i < EXPENSES.length ? EXPENSES[i] : EXPENSES[random.nextInt(EXPENSES.length)];
            var account = random.nextInt(100) < 85 ? checkingId : savingsId;
            var amount = money(-(5 + random.nextInt(75)) - random.nextInt(100) / 100.0);
            rows.add(row(account, amount, first.plusDays(random.nextInt(HISTORY_DAYS)), category,
                    label(category) + " " + (i + 1), country(random)));
        }
        rows.add(new CreateTransactionRequest(checkingId, ABNORMAL_AMOUNT, today.minusDays(1),
                Category.DINING, "Anniversary dinner", "US"));
        return new SyntheticDataPlan(rows);
    }

    /** All planned transactions, in insert order. */
    public List<CreateTransactionRequest> transactions() {
        return transactions;
    }

    /** Index of the planned abnormal transaction (the last one). */
    public int abnormalIndex() {
        return transactions.size() - 1;
    }

    private static CreateTransactionRequest row(long account, String amount, LocalDate date,
                                                Category category, String description, String country) {
        return new CreateTransactionRequest(account, new BigDecimal(amount), date, category, description, country);
    }

    private static String money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** About 70 % US, 20 % GB, 10 % CA. */
    private static String country(Random random) {
        int n = random.nextInt(10);
        return n < 7 ? "US" : n < 9 ? "GB" : "CA";
    }

    private static String label(Category category) {
        return switch (category) {
            case GROCERIES -> "Grocery store";
            case TRANSPORT -> "Taxi";
            case UTILITIES -> "Utility bill";
            case DINING -> "Restaurant";
            case ENTERTAINMENT -> "Cinema";
            case INCOME -> "Income";
            case OTHER -> "Purchase";
        };
    }
}
