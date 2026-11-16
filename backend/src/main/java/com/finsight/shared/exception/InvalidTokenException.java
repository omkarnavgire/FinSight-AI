package com.finsight.shared.exception;

/**
 * Thrown when a refresh token is invalid, expired, revoked, or cannot be found.
 *
 * <p>Maps to HTTP 401 Unauthorized via {@link com.finsight.shared.advice.GlobalExceptionHandler}.
 * The exception message is never included in the response body to avoid leaking token details.
 */
public class InvalidTokenException extends FinSightException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
