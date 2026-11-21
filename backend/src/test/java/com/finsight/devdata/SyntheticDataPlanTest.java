package com.finsight.devdata;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link SyntheticDataPlan} (T-61, FR-02-4, Q-1). */
class SyntheticDataPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final long CHECKING = 1L;
    private static final long SAVINGS = 2L;

    private final SyntheticDataPlan plan = SyntheticDataPlan.build(TODAY, CHECKING, SAVINGS);

    @Test
    @DisplayName("Exactly 180 transactions, all dated within the last 90 days")
    void count_and_window() {
        assertThat(plan.transactions()).hasSize(180);
        assertThat(plan.transactions()).allSatisfy(t -> assertThat(t.transactionDate())
                .isBetween(TODAY.minusDays(89), TODAY));
    }

    @Test
    @DisplayName("Every category appears; country codes are US, GB and CA only; both accounts are used")
    void coverage() {
        assertThat(plan.transactions().stream().map(CreateTransactionRequest::category).collect(Collectors.toSet()))
                .isEqualTo(EnumSet.allOf(Category.class));
        assertThat(plan.transactions().stream().map(CreateTransactionRequest::countryCode).collect(Collectors.toSet()))
                .isSubsetOf("US", "GB", "CA").contains("US", "GB", "CA");
        assertThat(plan.transactions().stream().map(CreateTransactionRequest::accountId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(CHECKING, SAVINGS);
    }

    @Test
    @DisplayName("Amounts are non-zero with at most 2 decimals; every category is set explicitly")
    void amountsAndCategories_valid() {
        assertThat(plan.transactions()).allSatisfy(t -> {
            assertThat(t.amount().signum()).isNotZero();
            assertThat(t.amount().scale()).isLessThanOrEqualTo(2);
            assertThat(t.category()).isNotNull();
        });
    }

    @Test
    @DisplayName("The same day always gives the same plan")
    void deterministic() {
        assertThat(SyntheticDataPlan.build(TODAY, CHECKING, SAVINGS).transactions()).isEqualTo(plan.transactions());
    }

    @Test
    @DisplayName("The planned abnormal row really meets the ABNORMAL_AMOUNT rule on the history before it (C-2)")
    void abnormalRow_meetsRule() {
        var abnormal = plan.transactions().get(plan.abnormalIndex());
        assertThat(abnormal.category()).isEqualTo(Category.DINING);
        assertThat(abnormal.amount()).isEqualByComparingTo(SyntheticDataPlan.ABNORMAL_AMOUNT);

        var history = plan.transactions().subList(0, plan.abnormalIndex()).stream()
                .filter(t -> t.category() == Category.DINING)
                .filter(t -> !t.transactionDate().isBefore(TODAY.minusDays(29)))
                .toList();
        long n = history.size();
        var s = history.stream().map(CreateTransactionRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(n).as("n >= 5").isGreaterThanOrEqualTo(5);
        assertThat(s.signum()).as("same sign as the abnormal debit").isEqualTo(abnormal.amount().signum());
        assertThat(abnormal.amount().abs().multiply(BigDecimal.valueOf(n)))
                .as("|amount| * n > 3 * |s|").isGreaterThan(s.abs().multiply(BigDecimal.valueOf(3)));
    }
}
