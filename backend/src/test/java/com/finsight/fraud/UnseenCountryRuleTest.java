package com.finsight.fraud;

import com.finsight.fraud.domain.UnseenCountryRule;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for {@link UnseenCountryRule} (T-57, AC-FRAUD-4). */
@ExtendWith(MockitoExtension.class)
class UnseenCountryRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0);

    @Mock private FraudHistory history;

    private final UnseenCountryRule rule = new UnseenCountryRule();

    private boolean fires(String country) {
        return rule.fires(new FraudCandidate(5L, new BigDecimal("-10.00"), "OTHER", "x", country), history, NOW);
    }

    @Test
    @DisplayName("First-ever transaction: skipped, and the country is not even looked up")
    void firstTransaction_skipped() {
        when(history.hasAnyTransaction()).thenReturn(false);

        assertThat(fires("JP")).isFalse();
        verify(history, never()).hasUsedCountry(anyString());
    }

    @Test
    @DisplayName("A country the user used before passes")
    void knownCountry_passes() {
        when(history.hasAnyTransaction()).thenReturn(true);
        when(history.hasUsedCountry("US")).thenReturn(true);

        assertThat(fires("US")).isFalse();
    }

    @Test
    @DisplayName("A country the user never used fires")
    void newCountry_fires() {
        when(history.hasAnyTransaction()).thenReturn(true);
        when(history.hasUsedCountry("JP")).thenReturn(false);

        assertThat(fires("JP")).isTrue();
    }
}
