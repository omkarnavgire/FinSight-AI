package com.finsight.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A fired fraud rule for one transaction ({@code fraud_alerts}, V7; V7 is the source of truth).
 *
 * <p>{@code userId} and {@code transactionId} are plain IDs: {@code users} and {@code transactions}
 * belong to other modules (no cross-module FK). Only the acknowledgement can change after creation.
 */
@Entity
@Table(name = "fraud_alerts")
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_name", nullable = false, updatable = false, length = 100)
    private FraudRuleName ruleName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Severity severity;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean acknowledged;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected FraudAlert() {}

    /**
     * Creates a new, unacknowledged alert with the rule's fixed severity and text.
     *
     * @param userId        owner, from the security context
     * @param transactionId the transaction that fired the rule; already inserted
     * @param rule          the fired rule
     * @param createdAt     creation time
     * @return a new, unsaved alert
     */
    public static FraudAlert create(Long userId, Long transactionId, FraudRuleName rule, LocalDateTime createdAt) {
        var a = new FraudAlert();
        a.userId = userId;
        a.transactionId = transactionId;
        a.ruleName = rule;
        a.severity = rule.severity();
        a.description = rule.alertText();
        a.acknowledged = false;
        a.createdAt = createdAt;
        return a;
    }

    /**
     * Marks the alert as acknowledged (FR-07-5). Repeating it changes nothing: the first
     * acknowledgement time is kept.
     */
    public void acknowledge(LocalDateTime at) {
        if (acknowledged) {
            return;
        }
        acknowledged = true;
        acknowledgedAt = at;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    public Long getTransactionId() { return transactionId; }

    public FraudRuleName getRuleName() { return ruleName; }

    public Severity getSeverity() { return severity; }

    public String getDescription() { return description; }

    public boolean isAcknowledged() { return acknowledged; }

    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
