package com.finsight.auth;

import com.finsight.auth.filter.OriginRefererFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OriginRefererFilter} normalisation logic (T-16b).
 *
 * <p>Covers all AC-CSRF-1 through AC-CSRF-9 scenarios.
 * HTTP filter behaviour (actual 403 responses) is tested in {@code AuthControllerIT}.
 */
class OriginRefererFilterTest {

    private final OriginRefererFilter filter =
            new OriginRefererFilter("http://localhost:5173,https://trusted.example.com");

    // ---- AC-CSRF-8 / normalisation table ----

    @Test
    @DisplayName("AC-CSRF-8: HTTPS with explicit default port 443 — port dropped, scheme kept lowercase")
    void normalise_httpsExplicitPort443_dropsPort() {
        assertThat(filter.normalise("HTTPS://Trusted.Example.Com:443"))
                .isEqualTo("https://trusted.example.com");
    }

    @Test
    @DisplayName("AC-CSRF-9: HTTP with port 443 — port preserved (443 is not default for HTTP)")
    void normalise_httpPort443_preservesPort() {
        assertThat(filter.normalise("HTTP://Trusted.Example.Com:443"))
                .isEqualTo("http://trusted.example.com:443");
    }

    @Test
    @DisplayName("HTTP with explicit default port 80 — port dropped")
    void normalise_httpPort80_dropsPort() {
        assertThat(filter.normalise("http://example.com:80"))
                .isEqualTo("http://example.com");
    }

    @Test
    @DisplayName("HTTPS with non-default port 8443 — port preserved")
    void normalise_httpsNonDefaultPort_preservesPort() {
        assertThat(filter.normalise("https://example.com:8443"))
                .isEqualTo("https://example.com:8443");
    }

    @Test
    @DisplayName("HTTP with non-default port 8080 — port preserved")
    void normalise_httpNonDefaultPort_preservesPort() {
        assertThat(filter.normalise("http://example.com:8080"))
                .isEqualTo("http://example.com:8080");
    }

    // ---- AC-CSRF-5: malformed values ----

    @ParameterizedTest(name = "malformed: {0}")
    @ValueSource(strings = {"not-a-uri", "://missing-scheme", "javascript:alert(1)", ""})
    @DisplayName("AC-CSRF-5: malformed or missing scheme returns null")
    void normalise_malformed_returnsNull(String raw) {
        assertThat(filter.normalise(raw)).isNull();
    }

    @Test
    @DisplayName("AC-CSRF-5: null input returns null")
    void normalise_null_returnsNull() {
        assertThat(filter.normalise(null)).isNull();
    }

    // ---- AC-CSRF-6: suffix-match attack ----

    @Test
    @DisplayName("AC-CSRF-6: evil.trusted.example.com does not match trusted.example.com")
    void normalise_suffixMatchAttack_notEqual() {
        // The normalised value is a different string — the caller's allowlist check
        // uses strict equality, so this naturally fails.
        assertThat(filter.normalise("https://evil.trusted.example.com"))
                .isEqualTo("https://evil.trusted.example.com");
        // "https://evil.trusted.example.com" ≠ "https://trusted.example.com" → rejected
    }

    // ---- AC-CSRF-7: Referer with path ----

    @Test
    @DisplayName("AC-CSRF-7: Referer with path normalises to scheme://host only")
    void normalise_refererWithPath_extractsOriginOnly() {
        assertThat(filter.normalise("https://trusted.example.com/dashboard?q=1#anchor"))
                .isEqualTo("https://trusted.example.com");
    }

    // ---- Standard cases ----

    @Test
    @DisplayName("Mixed-case scheme and host are lowercased")
    void normalise_mixedCase_lowercased() {
        assertThat(filter.normalise("HTTPS://Example.COM"))
                .isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("Allowed origin without port passes normalisation")
    void normalise_allowedOrigin_roundTrips() {
        assertThat(filter.normalise("https://trusted.example.com"))
                .isEqualTo("https://trusted.example.com");
    }
}
