package com.finsight.shared;

import com.finsight.shared.country.CountryCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CountryCodes} (T-17).
 *
 * <p>Verifies the static ISO 3166-1 alpha-2 allowlist: valid codes pass,
 * invalid / malformed / null codes are rejected.
 */
class CountryCodesTest {

    @ParameterizedTest(name = "valid code: {0}")
    @ValueSource(strings = {"US", "GB", "DE", "JP", "IN", "ZA", "AU", "BR", "FR", "CN"})
    @DisplayName("Common valid ISO 3166-1 alpha-2 codes are accepted")
    void validCodes_areAccepted(String code) {
        assertThat(CountryCodes.isValid(code)).isTrue();
    }

    @Test
    @DisplayName("All 249 assigned codes must be accepted — spot-check boundary entries")
    void boundaryEntriesAccepted() {
        // First alphabetically
        assertThat(CountryCodes.isValid("AD")).isTrue();
        // Last alphabetically
        assertThat(CountryCodes.isValid("ZW")).isTrue();
    }

    @Test
    @DisplayName("null is rejected")
    void null_isRejected() {
        assertThat(CountryCodes.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Empty string is rejected")
    void emptyString_isRejected() {
        assertThat(CountryCodes.isValid("")).isFalse();
    }

    @ParameterizedTest(name = "invalid: ''{0}''")
    @ValueSource(strings = {"us", "gb", "Gb", "uS"})
    @DisplayName("Lowercase and mixed-case codes are rejected (validation is case-sensitive)")
    void lowercase_isRejected(String code) {
        assertThat(CountryCodes.isValid(code)).isFalse();
    }

    @ParameterizedTest(name = "invalid: ''{0}''")
    @ValueSource(strings = {"ZZ", "XX", "QQ", "AA", "1A", "A1", "A", "USA", "U S"})
    @DisplayName("Non-assigned, numeric, 1-char, 3-char, and spaced codes are rejected")
    void nonAssigned_isRejected(String code) {
        assertThat(CountryCodes.isValid(code)).isFalse();
    }

    @Test
    @DisplayName("Single whitespace string is rejected")
    void whitespace_isRejected() {
        assertThat(CountryCodes.isValid(" ")).isFalse();
    }
}
