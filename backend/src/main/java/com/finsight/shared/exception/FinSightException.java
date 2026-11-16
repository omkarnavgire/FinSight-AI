package com.finsight.shared.exception;

/**
 * Base exception for all FinSight domain errors.
 *
 * <p>Subclasses map to specific HTTP status codes via
 * {@link com.finsight.shared.advice.GlobalExceptionHandler}.
 */
public abstract class FinSightException extends RuntimeException {

    protected FinSightException(String message) {
        super(message);
    }

    protected FinSightException(String message, Throwable cause) {
        super(message, cause);
    }
}
