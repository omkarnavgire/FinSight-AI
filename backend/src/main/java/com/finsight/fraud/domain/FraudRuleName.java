package com.finsight.fraud.domain;

/**
 * The fraud rules, as stored in {@code fraud_alerts.rule_name} (V7 is the source of truth).
 *
 * <p>Each rule has a fixed severity and a fixed alert text (Q-8). The text never contains the
 * user's own transaction description or any other user-supplied value.
 */
public enum FraudRuleName {

    DUPLICATE_TRANSACTION(Severity.HIGH,
            "Possible duplicate: same account, amount and description within 60 seconds."),
    ABNORMAL_AMOUNT(Severity.MEDIUM,
            "Amount is more than 3 times the average for this category over the last 30 days."),
    UNSEEN_COUNTRY(Severity.MEDIUM,
            "Country not seen in any of your earlier transactions.");

    private final Severity severity;
    private final String alertText;

    FraudRuleName(Severity severity, String alertText) {
        this.severity = severity;
        this.alertText = alertText;
    }

    /** Severity recorded on the alert. */
    public Severity severity() {
        return severity;
    }

    /** Fixed alert description recorded on the alert. */
    public String alertText() {
        return alertText;
    }
}
