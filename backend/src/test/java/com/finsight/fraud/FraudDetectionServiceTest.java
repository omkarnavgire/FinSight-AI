package com.finsight.fraud;

import com.finsight.fraud.domain.AbnormalAmountRule;
import com.finsight.fraud.domain.DuplicateTransactionRule;
import com.finsight.fraud.domain.FraudAlert;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.domain.Severity;
import com.finsight.fraud.domain.UnseenCountryRule;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.repository.FraudAlertRepository;
import com.finsight.fraud.service.FraudDetectionService;
import com.finsight.fraud.service.FraudHistory;
import com.finsight.fraud.service.FraudHistory.CategoryStats;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests for {@link FraudDetectionService} (T-52, AC-ORD-3, C-3). History and repository are mocked. */
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    private static final long CALLER = 51L;
    private static final FraudCandidate CANDIDATE =
            new FraudCandidate(5L, new BigDecimal("-900.00"), "DINING", "Dinner", "JP");

    @Mock private FraudHistory history;
    @Mock private FraudAlertRepository alertRepository;

    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        service = new FraudDetectionService(history, alertRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void givenHistory(boolean duplicate, CategoryStats stats, boolean anyTx, boolean usedCountry) {
        when(history.latestDuplicateCreatedAt(any(), any(), any()))
                .thenReturn(duplicate ? Optional.of(LocalDateTime.now().minusSeconds(5)) : Optional.empty());
        when(history.categoryStats(eq("DINING"), any(), any())).thenReturn(stats);
        when(history.hasAnyTransaction()).thenReturn(anyTx);
        lenient().when(history.hasUsedCountry("JP")).thenReturn(usedCountry);
    }

    @Test
    @DisplayName("All three rules fire: names are returned in rule order")
    void allRulesFire() {
        givenHistory(true, new CategoryStats(5, new BigDecimal("-100.00")), true, false);

        assertThat(service.evaluate(CANDIDATE)).containsExactly(
                FraudRuleName.DUPLICATE_TRANSACTION, FraudRuleName.ABNORMAL_AMOUNT, FraudRuleName.UNSEEN_COUNTRY);
    }

    @Test
    @DisplayName("Every rule is evaluated even when none fires")
    void noRuleFires_allEvaluated() {
        givenHistory(false, new CategoryStats(4, new BigDecimal("-40.00")), true, true);

        assertThat(service.evaluate(CANDIDATE)).isEmpty();
        verify(history).latestDuplicateCreatedAt(5L, new BigDecimal("-900.00"), "Dinner");
        verify(history).categoryStats(eq("DINING"), any(), any());
        verify(history).hasUsedCountry("JP");
    }

    @Test
    @DisplayName("evaluate never writes: the alert repository is untouched")
    void evaluate_isReadOnly() {
        givenHistory(true, new CategoryStats(5, new BigDecimal("-100.00")), true, false);

        service.evaluate(CANDIDATE);

        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("recordAlerts saves one alert per fired rule, owned by the caller, with the rule's severity and text")
    void recordAlerts_savesOnePerRule() {
        service.recordAlerts(77L, List.of(FraudRuleName.DUPLICATE_TRANSACTION, FraudRuleName.UNSEEN_COUNTRY));

        var captor = ArgumentCaptor.forClass(FraudAlert.class);
        verify(alertRepository, times(2)).save(captor.capture());
        var alerts = captor.getAllValues();
        assertThat(alerts).extracting(FraudAlert::getUserId).containsOnly(CALLER);
        assertThat(alerts).extracting(FraudAlert::getTransactionId).containsOnly(77L);
        assertThat(alerts).extracting(FraudAlert::getRuleName)
                .containsExactly(FraudRuleName.DUPLICATE_TRANSACTION, FraudRuleName.UNSEEN_COUNTRY);
        assertThat(alerts).extracting(FraudAlert::getSeverity).containsExactly(Severity.HIGH, Severity.MEDIUM);
        assertThat(alerts).allSatisfy(a -> {
            assertThat(a.isAcknowledged()).isFalse();
            assertThat(a.getDescription()).doesNotContain("Dinner");
        });
    }

    @Test
    @DisplayName("recordAlerts with no fired rules saves nothing")
    void recordAlerts_empty_savesNothing() {
        service.recordAlerts(77L, List.of());

        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("C-3: evaluate and recordAlerts are MANDATORY, never read-only, never REQUIRES_NEW, never noRollbackFor")
    void transactionalSettings() throws Exception {
        for (var method : List.of(
                FraudDetectionService.class.getMethod("evaluate", FraudCandidate.class),
                FraudDetectionService.class.getMethod("recordAlerts", Long.class, List.class))) {
            var tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
    }

    @Test
    @DisplayName("Rules are stateless: no instance fields")
    void rulesAreStateless() {
        assertThat(Stream.of(DuplicateTransactionRule.class, AbnormalAmountRule.class, UnseenCountryRule.class)
                .flatMap(c -> Stream.of(c.getDeclaredFields())))
                .allMatch(f -> Modifier.isStatic(f.getModifiers()));
    }
}
