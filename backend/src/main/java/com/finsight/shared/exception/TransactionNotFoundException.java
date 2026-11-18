package com.finsight.shared.exception;

/**
 * Thrown when a transaction does not exist or belongs to another user's account.
 *
 * <p>Both cases map to HTTP 404 so the existence of another user's data is never revealed (AC-OWN-1).
 */
public class TransactionNotFoundException extends NotFoundException {

    public TransactionNotFoundException(Long transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
