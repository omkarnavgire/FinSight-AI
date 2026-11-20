package com.finsight.fraud.dto;

import com.finsight.fraud.domain.FraudAlert;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.domain.Severity;

import java.time.LocalDateTime;

/**
 * API view of a fraud alert (FR-07-4). Carries no user ID.
 *
 * @param id             alert ID
 * @param transactionId  the transaction that fired the rule
 * @param rule           fired rule
 * @param severity       rule severity
 * @param description    fixed alert text
 * @param acknowledged   {@code true} once dismissed
 * @param acknowledgedAt first acknowledgement time, or {@code null}
 * @param createdAt      when the alert was created
 */
public record FraudAlertDto(Long id,
                            Long transactionId,
                            FraudRuleName rule,
                            Severity severity,
                            String description,
                            boolean acknowledged,
                            LocalDateTime acknowledgedAt,
                            LocalDateTime createdAt) {

    /** Maps an entity to its API view. */
    public static FraudAlertDto from(FraudAlert a) {
        return new FraudAlertDto(a.getId(), a.getTransactionId(), a.getRuleName(), a.getSeverity(),
                a.getDescription(), a.isAcknowledged(), a.getAcknowledgedAt(), a.getCreatedAt());
    }
}
