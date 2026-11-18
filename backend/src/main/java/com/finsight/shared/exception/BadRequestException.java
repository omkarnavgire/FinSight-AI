package com.finsight.shared.exception;

/**
 * Thrown when a request is well-formed JSON but breaks a business validation rule
 * that Bean Validation cannot express (e.g. a zero amount, an inverted filter range).
 *
 * <p>Maps to HTTP 400 via {@link com.finsight.shared.advice.GlobalExceptionHandler}.
 * Messages must never echo raw client input.
 */
public class BadRequestException extends FinSightException {

    public BadRequestException(String message) {
        super(message);
    }
}
