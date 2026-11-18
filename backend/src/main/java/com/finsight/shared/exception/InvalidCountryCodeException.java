package com.finsight.shared.exception;

/**
 * Thrown when a {@code countryCode} is not in the ISO 3166-1 alpha-2 allowlist
 * ({@link com.finsight.shared.country.CountryCodes}).
 *
 * <p>Maps to HTTP 400 and is raised before any database write (AC-FRAUD-5).
 * The message does not repeat the submitted value.
 */
public class InvalidCountryCodeException extends BadRequestException {

    public InvalidCountryCodeException() {
        super("countryCode is not a valid ISO 3166-1 alpha-2 code");
    }
}
