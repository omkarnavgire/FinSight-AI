package com.finsight.fraud;

import com.finsight.fraud.domain.AbnormalAmountRule;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;
import com.finsight.fraud.service.FraudHistory.CategoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Unit tests for {@link AbnormalAmountRule} (T-56, AC-FRAUD-2, 2b, 2c, C-2). */
@ExtendWith(MockitoExtension.class)
class AbnormalAmountRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0);
    private static final LocalDate FROM = LocalDate.of(2026, 2, 14); // today - 29
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Mock private FraudHistory history;

    private final AbnormalAmountRule rule = new AbnormalAmountRule();

    private boolean fires(String amount, long count, String sum) {
        when(history.categoryStats("DINING", FROM, TODAY)).thenReturn(new CategoryStats(count, new BigDecimal(sum)));
        return rule.fires(new FraudCandidate(5L, new BigDecimal(amount), "DINING", "x", "US"), history, NOW);
    }

    @Test
    @DisplayName("Exactly 4 category transactions in the window: skipped, even for a huge amount")
    void fourTransactions_skipped() {
        assertThat(fires("-100000.00", 4, "-40.00")).isFalse();
    }

    @Test
    @DisplayName("Exactly 5 transactions: evaluated, and fires when |amount| > 3 x |mean|")
    void fiveTransactions_firesAboveThreeTimesMean() {
        // mean = -100 / 5 = -20; 3 x 20 = 60; 61 > 60
        assertThat(fires("-61.00", 5, "-100.00")).isTrue();
    }

    @Test
    @DisplayName("Exactly 3 x mean does not fire (strictly greater is required)")
    void exactlyThreeTimesMean_doesNotFire() {
        assertThat(fires("-60.00", 5, "-100.00")).isFalse();
    }

    @Test
    @DisplayName("5 or more transactions with |amount| <= 3 x |mean|: no alert")
    void belowThreshold_noAlert() {
        assertThat(fires("-25.00", 8, "-160.00")).isFalse();
    }

    @Test
    @DisplayName("Zero signed sum (mean = 0): skipped")
    void zeroSum_skipped() {
        assertThat(fires("-500.00", 6, "0.00")).isFalse();
    }

    @Test
    @DisplayName("Opposite sign to the category mean: does not fire")
    void oppositeSign_doesNotFire() {
        assertThat(fires("500.00", 5, "-100.00")).isFalse();
    }

    @Test
    @DisplayName("Credits are evaluated the same way as debits")
    void credits_fire() {
        assertThat(fires("700.00", 5, "1000.00")).isTrue(); // 700 x 5 = 3500 > 3000
    }

    @Test
    @DisplayName("The window is today-29 to today, in the candidate's category")
    void usesThirtyDayWindowAndCategory() {
        fires("-10.00", 0, "0");

        verify(history).categoryStats("DINING", FROM, TODAY);
        verifyNoMoreInteractions(history);
    }

    @Test
    @DisplayName("The minimum history is the hardcoded constant 5; no configuration property is read")
    void thresholdIsHardcoded() {
        assertThat(AbnormalAmountRule.MIN_HISTORY).isEqualTo(5);
        assertThat(Arrays.stream(AbnormalAmountRule.class.getDeclaredFields()))
                .noneMatch(f -> f.isAnnotationPresent(Value.class));
        assertThat(AbnormalAmountRule.class.getDeclaredConstructors())
                .allMatch(c -> c.getParameterCount() == 0);
    }
}
