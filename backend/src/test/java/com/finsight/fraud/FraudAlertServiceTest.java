package com.finsight.fraud;

import com.finsight.fraud.domain.FraudAlert;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.repository.FraudAlertRepository;
import com.finsight.fraud.service.FraudAlertService;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.FraudAlertNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests for {@link FraudAlertService} (T-53): ownership, paging and idempotent acknowledge. */
@ExtendWith(MockitoExtension.class)
class FraudAlertServiceTest {

    private static final long CALLER = 61L;

    @Mock private FraudAlertRepository alertRepository;

    private FraudAlertService service;

    @BeforeEach
    void setUp() {
        service = new FraudAlertService(alertRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static FraudAlert alert() {
        return FraudAlert.create(CALLER, 9L, FraudRuleName.UNSEEN_COUNTRY, LocalDateTime.of(2026, 3, 15, 10, 0));
    }

    @Test
    @DisplayName("List queries only the caller's unacknowledged alerts and caps the page size at 100")
    void list_ownerScopedAndCapped() {
        when(alertRepository.findByUserIdAndAcknowledgedFalseOrderByCreatedAtDescIdDesc(CALLER, PageRequest.of(1, 100)))
                .thenReturn(new PageImpl<>(List.of(alert()), PageRequest.of(1, 100), 101));

        var page = service.listActive(1, 500);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().rule()).isEqualTo(FraudRuleName.UNSEEN_COUNTRY);
    }

    @Test
    @DisplayName("A negative page or a size below 1 is 400 and runs no query")
    void list_invalidPaging_400() {
        assertThatThrownBy(() -> service.listActive(-1, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.listActive(0, 0)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("Acknowledging a missing or foreign alert is 404")
    void acknowledge_notOwned_404() {
        when(alertRepository.findByIdAndUserId(3L, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(3L)).isInstanceOf(FraudAlertNotFoundException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Acknowledge sets the flag and time; repeating it keeps the first time")
    void acknowledge_idempotent() {
        var a = alert();
        when(alertRepository.findByIdAndUserId(3L, CALLER)).thenReturn(Optional.of(a));

        var first = service.acknowledge(3L);
        var second = service.acknowledge(3L);

        assertThat(first.acknowledged()).isTrue();
        assertThat(first.acknowledgedAt()).isNotNull();
        assertThat(second.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
    }
}
