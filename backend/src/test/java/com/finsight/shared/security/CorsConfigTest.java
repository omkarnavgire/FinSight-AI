package com.finsight.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for CORS origin handling in {@link SecurityConfig} (T-62, NFR-01-5). */
class CorsConfigTest {

    private static CorsConfiguration corsFor(String origins) {
        var config = new SecurityConfig(null, null, origins, true, 31536000, true, "api.finsight.example");
        return config.corsConfigurationSource().getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/accounts"));
    }

    @Test
    @DisplayName("Origins are split, trimmed and blank entries dropped")
    void parse_trimsAndDropsBlanks() {
        assertThat(SecurityConfig.parseOrigins(" https://a.example , https://b.example:8443,, "))
                .containsExactly("https://a.example", "https://b.example:8443");
        assertThat(SecurityConfig.parseOrigins(null)).isEmpty();
    }

    @Test
    @DisplayName("A wildcard origin is refused in every profile")
    void wildcard_refused() {
        assertThatThrownBy(() -> SecurityConfig.parseOrigins("https://a.example,*"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.parseOrigins("https://*.example"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Only the exact listed origin is allowed: http twin, subdomain, suffix lookalike and null are refused")
    void exactMatchOnly() {
        var cors = corsFor("https://app.finsight.example");

        assertThat(cors.checkOrigin("https://app.finsight.example")).isEqualTo("https://app.finsight.example");
        for (var other : List.of("http://app.finsight.example", "https://evil.app.finsight.example",
                "https://app.finsight.example.evil.com", "https://app.finsight.example:8443", "null")) {
            assertThat(cors.checkOrigin(other)).as(other).isNull();
        }
    }

    @Test
    @DisplayName("No origin patterns are used, and credentials stay enabled for the refresh cookie")
    void noPatterns() {
        var cors = corsFor("https://app.finsight.example");

        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isTrue();
    }
}
