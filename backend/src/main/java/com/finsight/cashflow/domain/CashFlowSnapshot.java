package com.finsight.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One stored forecast for one user and one horizon ({@code cash_flow_snapshots}, V6).
 *
 * <p>{@code userId} is a plain ID: {@code users} belongs to the auth module (no cross-module FK).
 * Rows are immutable once written; a recalculation replaces them (D-9).
 * {@code forecastJson} holds {@code [{"date":"YYYY-MM-DD","projected_balance":n}, ...]}.
 */
@Entity
@Table(name = "cash_flow_snapshots")
public class CashFlowSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    @Column(name = "horizon_days", nullable = false, updatable = false)
    private byte horizonDays;

    @Column(name = "low_confidence", nullable = false, updatable = false)
    private boolean lowConfidence;

    @Column(name = "confidence_reason", length = 50, updatable = false)
    private String confidenceReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forecast_json", nullable = false, updatable = false)
    private String forecastJson;

    protected CashFlowSnapshot() {}

    /**
     * Creates a new, unsaved snapshot.
     *
     * @param userId           owner, from the security context
     * @param horizon          forecast length
     * @param generatedAt      calculation time
     * @param lowConfidence    {@code true} when history is shorter than 30 days or absent
     * @param confidenceReason {@code null} or {@code "NO_HISTORY"}
     * @param forecastJson     serialised daily points
     * @return the snapshot
     */
    public static CashFlowSnapshot create(Long userId, ForecastHorizon horizon, LocalDateTime generatedAt,
                                          boolean lowConfidence, String confidenceReason, String forecastJson) {
        var s = new CashFlowSnapshot();
        s.userId = userId;
        s.horizonDays = (byte) horizon.days();
        s.generatedAt = generatedAt;
        s.lowConfidence = lowConfidence;
        s.confidenceReason = confidenceReason;
        s.forecastJson = forecastJson;
        return s;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }

    public ForecastHorizon getHorizon() { return ForecastHorizon.ofDays(horizonDays); }

    public boolean isLowConfidence() { return lowConfidence; }

    public String getConfidenceReason() { return confidenceReason; }

    public String getForecastJson() { return forecastJson; }
}
