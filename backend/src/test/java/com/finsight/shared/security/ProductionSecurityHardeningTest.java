package com.finsight.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit fixes in {@link ProductionSecurityValidator}: H-1 (fail closed outside a test JVM) and
 * M-1 (required public host for HTTPS redirects). The M-2 proxy-format cases live in
 * {@code ProductionSecurityValidatorTest}.
 */
class ProductionSecurityHardeningTest {

    private static List<String> problems(MockEnvironment env) {
        return ProductionSecurityValidator.problems(env, Set.of(env.getActiveProfiles()));
    }

    /** Evaluates as the packaged application does: no JUnit on the classpath. */
    private static List<String> packagedApp(MockEnvironment env) {
        return ProductionSecurityValidator.problems(env, Set.of(env.getActiveProfiles()), false);
    }

    // ---- M-1: public host ----

    @ParameterizedTest(name = "publicHost=[{0}]")
    @ValueSource(strings = {"", "https://api.finsight.example", "api.finsight.example/path", "*.finsight.example",
            "api finsight.example", "api.finsight.example:abc", "-api.finsight.example"})
    @DisplayName("M-1: PUBLIC_HOST is required and must be a bare host or host:port")
    void publicHost_invalid(String host) {
        var env = ProductionSecurityValidatorTest.valid();
        env.setProperty("finsight.security.public-host", host);
        assertThat(problems(env)).anyMatch(p -> p.contains("PUBLIC_HOST"));
    }

    @Test
    @DisplayName("M-1: host:port is accepted")
    void publicHost_withPort_passes() {
        var env = ProductionSecurityValidatorTest.valid();
        env.setProperty("finsight.security.public-host", "api.finsight.example:8443");
        assertThat(problems(env)).isEmpty();
    }

    // ---- H-1: fail closed outside a test JVM ----

    @Test
    @DisplayName("H-1: in the packaged app the 'test' profile does not skip the checks")
    void packaged_testProfile_enforced() {
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("finsight.security.require-https", "false");
        assertThat(packagedApp(env)).anyMatch(p -> p.contains("FINSIGHT_SECURITY_REQUIRE_HTTPS"))
                .anyMatch(p -> p.contains("JWT_SECRET"));
    }

    @Test
    @DisplayName("H-1: in the packaged app require-https=false with no profile does not skip the checks")
    void packaged_httpsOffNoProfile_enforced() {
        var env = ProductionSecurityValidatorTest.valid();
        env.setActiveProfiles();
        env.setProperty("finsight.security.require-https", "false");
        assertThat(packagedApp(env)).anyMatch(p -> p.contains("FINSIGHT_SECURITY_REQUIRE_HTTPS"));
    }

    @Test
    @DisplayName("H-1: a fully valid configuration passes in the packaged app with any non-dev profile")
    void packaged_validConfig_passes() {
        var env = ProductionSecurityValidatorTest.valid();
        env.setActiveProfiles("staging");
        assertThat(packagedApp(env)).isEmpty();
    }

    @ParameterizedTest(name = "url host={0}")
    @ValueSource(strings = {"db.internal", "10.0.0.5", "prod-db.example.com"})
    @DisplayName("H-1: in the packaged app the dev profile refuses a non-localhost database, without echoing it")
    void packaged_devRemoteDb_refused(String host) {
        var env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://" + host + ":3306/finsight_dev?useSSL=false");
        assertThat(packagedApp(env)).anyMatch(p -> p.contains("dev profile may only use a database on localhost"))
                .noneMatch(p -> p.contains(host));
    }

    @ParameterizedTest(name = "url host={0}")
    @ValueSource(strings = {"localhost", "127.0.0.1", "[::1]"})
    @DisplayName("H-1: the dev profile with a localhost database starts normally")
    void packaged_devLocalDb_allowed(String host) {
        var env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://" + host + ":3306/finsight_dev?useSSL=false");
        assertThat(packagedApp(env)).isEmpty();
    }

    @Test
    @DisplayName("H-1: this JVM is detected as a test runtime (JUnit present)")
    void testRuntime_detected() {
        assertThat(ProductionSecurityValidator.isTestRuntime()).isTrue();
    }
}
