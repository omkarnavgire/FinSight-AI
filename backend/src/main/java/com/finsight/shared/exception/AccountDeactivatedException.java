package com.finsight.shared.exception;

/**
 * Thrown when a balance change is attempted on a deactivated account (FR-02-3, FR-03-6).
 *
 * <p>Maps to HTTP 409 Conflict. Only thrown after ownership has been confirmed,
 * so it never reveals another user's account.
 */
public class AccountDeactivatedException extends ConflictException {

    public AccountDeactivatedException(Long accountId) {
        super("Account is deactivated: " + accountId);
    }
}
