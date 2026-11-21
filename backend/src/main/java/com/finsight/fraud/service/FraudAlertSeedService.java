package com.finsight.fraud.service;

import com.finsight.fraud.domain.FraudAlert;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.repository.FraudAlertRepository;
import com.finsight.shared.ownership.SecurityUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Stores the single planned fraud alert of the demo data (T-61, Q-1). Exists only in {@code dev}.
 * The owner comes from the security context, like every other alert.
 */
@Service
@Profile("dev")
public class FraudAlertSeedService {

    private final FraudAlertRepository alertRepository;

    public FraudAlertSeedService(FraudAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /** Inserts one unacknowledged alert for an already-inserted transaction of the caller. */
    @Transactional
    public void seedAlert(Long transactionId, FraudRuleName rule) {
        alertRepository.save(FraudAlert.create(SecurityUtils.currentUserId(), transactionId, rule,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)));
    }
}
