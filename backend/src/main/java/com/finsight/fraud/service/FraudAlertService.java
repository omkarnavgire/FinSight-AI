package com.finsight.fraud.service;

import com.finsight.fraud.dto.FraudAlertDto;
import com.finsight.fraud.repository.FraudAlertRepository;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.FraudAlertNotFoundException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.shared.pagination.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Lists and acknowledges the caller's fraud alerts (T-53, FR-07-4, FR-07-5).
 *
 * <p>The caller always comes from {@link SecurityUtils#currentUserId()}; no method accepts a user ID.
 * Another user's alert behaves exactly like a missing one: {@link FraudAlertNotFoundException} (404).
 *
 * <p>Times are whole seconds, matching the {@code DATETIME} columns, so a returned value always
 * equals the stored one.
 */
@Service
public class FraudAlertService {

    static final int MAX_PAGE_SIZE = 100;

    private final FraudAlertRepository alertRepository;

    public FraudAlertService(FraudAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /**
     * Pages through the caller's unacknowledged alerts, newest first (Q-10).
     *
     * @param page zero-based page index, must be {@code >= 0}
     * @param size page size, must be {@code >= 1}; values above {@value #MAX_PAGE_SIZE} are capped
     * @return one page of alerts
     * @throws BadRequestException for a negative page or a size below 1 (400)
     */
    @Transactional(readOnly = true)
    public PageResponse<FraudAlertDto> listActive(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must not be negative");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1");
        }
        var result = alertRepository.findByUserIdAndAcknowledgedFalseOrderByCreatedAtDescIdDesc(
                SecurityUtils.currentUserId(), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return PageResponse.from(result.map(FraudAlertDto::from));
    }

    /**
     * Acknowledges (dismisses) one of the caller's alerts. Repeating it is safe and keeps the first
     * acknowledgement time (Q-10).
     *
     * @param alertId alert to acknowledge
     * @return the alert after acknowledgement
     * @throws FraudAlertNotFoundException if missing or not owned (404)
     */
    @Transactional
    public FraudAlertDto acknowledge(Long alertId) {
        var alert = alertRepository.findByIdAndUserId(alertId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new FraudAlertNotFoundException(alertId));
        alert.acknowledge(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        return FraudAlertDto.from(alert);
    }
}
