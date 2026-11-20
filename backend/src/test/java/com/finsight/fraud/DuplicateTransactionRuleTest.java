package com.finsight.fraud;

import com.finsight.fraud.domain.DuplicateTransactionRule;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Unit tests for {@link DuplicateTransactionRule} (T-55, AC-FRAUD-1, C-1): the exact 60 s boundary lives here. */
@ExtendWith(MockitoExtension.class)
class DuplicateTransactionRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0, 0, 500_000_000);
    private static final BigDecimal AMOUNT = new BigDecimal("-25.00");

    @Mock private FraudHistory history;

    private final DuplicateTransactionRule rule = new DuplicateTransactionRule();

    private static FraudCandidate candidate(String description) {
        return new FraudCandidate(5L, AMOUNT, "DINING", description, "US");
    }

    private boolean firesWithLatestAt(LocalDateTime latest) {
        when(history.latestDuplicateCreatedAt(5L, AMOUNT, "Lunch")).thenReturn(Optional.of(latest));
        return rule.fires(candidate("Lunch"), history, NOW);
    }

    @Test
    @DisplayName("No prior matching transaction: the rule is skipped")
    void noPrior_skipped() {
        when(history.latestDuplicateCreatedAt(5L, AMOUNT, "Lunch")).thenReturn(Optional.empty());

        assertThat(rule.fires(candidate("Lunch"), history, NOW)).isFalse();
    }

    @Test
    @DisplayName("A match 59 seconds ago fires")
    void before60s_fires() {
        assertThat(firesWithLatestAt(NOW.minusSeconds(59))).isTrue();
    }

    @Test
    @DisplayName("A match exactly 60 seconds ago fires (inclusive boundary)")
    void exactly60s_fires() {
        assertThat(firesWithLatestAt(NOW.minusSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("A match 60 seconds and 1 nanosecond ago does not fire")
    void justAfter60s_doesNotFire() {
        assertThat(firesWithLatestAt(NOW.minusSeconds(60).minusNanos(1))).isFalse();
    }

    @Test
    @DisplayName("A match 61 seconds ago does not fire")
    void after60s_doesNotFire() {
        assertThat(firesWithLatestAt(NOW.minusSeconds(61))).isFalse();
    }

    @Test
    @DisplayName("A null description is looked up as null (null matches null)")
    void nullDescription_passedThrough() {
        when(history.latestDuplicateCreatedAt(5L, AMOUNT, null)).thenReturn(Optional.of(NOW.minusSeconds(5)));

        assertThat(rule.fires(candidate(null), history, NOW)).isTrue();
        verify(history).latestDuplicateCreatedAt(5L, AMOUNT, null);
    }

    @Test
    @DisplayName("The rule only reads the duplicate lookup and reports its V7 name")
    void readsOnlyDuplicateLookup() {
        when(history.latestDuplicateCreatedAt(5L, AMOUNT, "Lunch")).thenReturn(Optional.empty());

        rule.fires(candidate("Lunch"), history, NOW);

        verify(history).latestDuplicateCreatedAt(5L, AMOUNT, "Lunch");
        verifyNoMoreInteractions(history);
        assertThat(rule.name()).isEqualTo(FraudRuleName.DUPLICATE_TRANSACTION);
    }
}
