package com.finsight.shared.exception;

/**
 * Thrown when an account does not exist or belongs to a different user.
 *
 * <p>Both cases map to HTTP 404 so the existence of another user's account
 * is never revealed (AC-OWN-1).
 */
public class AccountNotFoundException extends NotFoundException {

    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId);
    }
}
