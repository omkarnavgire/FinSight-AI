package com.finsight.shared.exception;

/**
 * Thrown when a fraud alert does not exist or belongs to a different user.
 *
 * <p>Both cases map to HTTP 404 so the existence of another user's alert is never revealed (AC-OWN-1).
 */
public class FraudAlertNotFoundException extends NotFoundException {

    public FraudAlertNotFoundException(Long alertId) {
        super("Fraud alert not found: " + alertId);
    }
}
