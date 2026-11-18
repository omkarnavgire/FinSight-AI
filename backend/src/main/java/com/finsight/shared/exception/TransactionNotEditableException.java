package com.finsight.shared.exception;

/**
 * Thrown when an edit or delete targets a non-manual (system-created) transaction.
 *
 * <p>FR-03-4 and FR-03-5 allow editing and deleting <em>manual</em> transactions only (decision D-3).
 * Maps to HTTP 409. Only thrown after ownership has been confirmed.
 */
public class TransactionNotEditableException extends ConflictException {

    public TransactionNotEditableException(Long transactionId) {
        super("Transaction is not a manual transaction and cannot be changed: " + transactionId);
    }
}
