package com.finsight.shared.exception;

/**
 * Base exception for requests that conflict with the current state of a resource.
 *
 * <p>Maps to HTTP 409 Conflict via {@link com.finsight.shared.advice.GlobalExceptionHandler}.
 * Example: writing to a deactivated account (FR-03-6).
 */
public abstract class ConflictException extends FinSightException {

    protected ConflictException(String message) {
        super(message);
    }
}
