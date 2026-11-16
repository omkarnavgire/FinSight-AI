package com.finsight.shared.exception;

/**
 * Thrown when login credentials are invalid.
 *
 * <p>Maps to HTTP 401 Unauthorized. Message is intentionally vague to prevent
 * user enumeration — always use "Invalid credentials" regardless of whether
 * the email or password was wrong.
 */
public class AuthenticationFailedException extends FinSightException {

    public AuthenticationFailedException() {
        super("Invalid credentials");
    }
}
