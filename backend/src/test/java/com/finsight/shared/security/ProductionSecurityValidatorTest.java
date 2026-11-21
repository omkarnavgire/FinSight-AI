package com.finsight.shared.security;

import com.finsight.support.TestTls;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ProductionSecurityValidator} (T-63, Q-4): every startup failure scenario. */
class ProductionSecurityValidatorTest {

    private static final String JWT = "unit-test-jwt-secret-that-is-at-least-32-bytes";
    private static TestTls tls;

    /** Shared TLS material, created once; also used by {@code ProductionSecurityHardeningTest}. */
    static synchronized TestTls tls() {
        if (tls == null) {
            tls = TestTls.create();
        }
        return tls;
    }

    @BeforeAll
    static void generateTls() {
        tls();
    }

    /** A fully valid production-style configuration. */
    static MockEnvironment valid() {
        var tls = tls();
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("finsight.security.require-https", "true");
        env.setProperty("finsight.security.cookie.secure", "true");
        env.setProperty("finsight.security.public-host", "api.finsight.example");
        env.setProperty("finsight.cors.allowed-origins", "https://app.finsight.example, https://admin.finsight.example:8443");
        env.setProperty("server.forward-headers-strategy", "native");
        env.setProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.10|10\\.0\\.0\\.11|fd00:0:0:0:0:0:0:10");
        env.setProperty("server.tomcat.remoteip.trusted-proxies", "");
        env.setProperty("finsight.security.trusted-proxy-count", "0");
        env.setProperty("finsight.security.jwt.secret", JWT);
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/finsight?serverTimezone=UTC");
        env.setProperty("spring.datasource.username", "app");
        env.setProperty("spring.datasource.password", "db-pass-for-unit-test");
        env.setProperty("spring.datasource.hikari.data-source-properties.sslMode", "VERIFY_IDENTITY");
        env.setProperty("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", "file:" + tls.truststore);
        env.setProperty("finsight.security.db-truststore.path", tls.truststore.toString());
        env.setProperty("finsight.security.db-truststore.password", TestTls.STORE_PASSWORD);
        env.setProperty("finsight.security.db-truststore.type", "PKCS12");
        return env;
    }

    private static List<String> problems(MockEnvironment env) {
        return ProductionSecurityValidator.problems(env, Set.of(env.getActiveProfiles()));
    }

    private static void assertProblem(MockEnvironment env, String expectedFragment) {
        assertThat(problems(env)).anyMatch(p -> p.contains(expectedFragment));
    }

    // ---- when the checks apply ----

    @Test
    @DisplayName("A fully valid production configuration passes")
    void valid_passes() {
        assertThat(problems(valid())).isEmpty();
    }

    @Test
    @DisplayName("dev and test profiles are local: nothing is enforced")
    void localProfiles_notEnforced() {
        for (var profile : new String[]{"dev", "test"}) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThat(problems(env)).as(profile).isEmpty();
        }
    }

    @Test
    @DisplayName("No profile with HTTPS required (the base default) is enforced: fail closed")
    void noProfile_enforced() {
        var env = new MockEnvironment();
        env.setProperty("finsight.security.require-https", "true");
        assertThat(problems(env)).isNotEmpty();
    }

    @Test
    @DisplayName("No profile with HTTPS switched off (test classpath default) is not enforced")
    void noProfile_httpsOff_notEnforced() {
        var env = new MockEnvironment();
        env.setProperty("finsight.security.require-https", "false");
        assertThat(problems(env)).isEmpty();
    }

    @Test
    @DisplayName("prod combined with a local profile is refused")
    void prodWithLocalProfile_refused() {
        var env = valid();
        env.setActiveProfiles("prod", "dev");
        assertProblem(env, "must not be combined");
    }

    // ---- HTTPS, cookie ----

    @Test
    @DisplayName("HTTPS enforcement off or an insecure cookie is refused in prod")
    void httpsAndCookie() {
        var env = valid();
        env.setProperty("finsight.security.require-https", "false");
        env.setProperty("finsight.security.cookie.secure", "false");
        assertProblem(env, "finsight.security.require-https");
        assertProblem(env, "COOKIE_SECURE");
    }

    // ---- CORS ----

    @ParameterizedTest(name = "origins={0}")
    @ValueSource(strings = {"", "*", "https://*.finsight.example", "http://app.finsight.example",
            "https://app.finsight.example/", "https://app.finsight.example/path", "https://user@app.finsight.example",
            "https://app.finsight.example?x=1", "https://app.finsight.example,", "null"})
    @DisplayName("CORS must list only exact https origins: no wildcard, http, path, user-info, query or blank entry")
    void cors_invalid(String origins) {
        var env = valid();
        env.setProperty("finsight.cors.allowed-origins", origins);
        assertProblem(env, "CORS_ALLOWED_ORIGINS");
    }

    @Test
    @DisplayName("CORS problems name the entry position, never the value")
    void cors_doesNotEchoValue() {
        var env = valid();
        env.setProperty("finsight.cors.allowed-origins", "http://evil-value.example");
        assertThat(problems(env)).noneMatch(p -> p.contains("evil-value"));
    }

    // ---- proxy ----

    @ParameterizedTest(name = "regex={0}")
    @ValueSource(strings = {"", ".*", ".+", "\\d+\\.\\d+\\.\\d+\\.\\d+", "(", "8\\.8\\.8\\.8",
            "10\\..*", "10\\.0\\.0\\..*", "10\\.0\\.0\\.(10|11)", "10.0.0.10", "10\\.0\\.0\\.256",
            "10\\.0\\.0\\.10|", "10\\.0\\.0\\.10|.*", "172\\.(1[6-9]|2[0-9]|3[0-1])\\..*", "fd00::10"})
    @DisplayName("M-2: TRUSTED_PROXY_REGEX must list exact proxy addresses: no ranges, wildcards, groups or bare dots")
    void proxyRegex_invalid(String regex) {
        var env = valid();
        env.setProperty("server.tomcat.remoteip.internal-proxies", regex);
        assertProblem(env, "TRUSTED_PROXY_REGEX");
    }

    @Test
    @DisplayName("M-2: a single exact IPv4 proxy address passes")
    void proxyRegex_singleAddress_passes() {
        var env = valid();
        env.setProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.10");
        assertThat(problems(env)).isEmpty();
    }

    // ---- JWT ----

    @Test
    @DisplayName("A JWT secret shorter than 32 bytes is refused, and its value is never printed")
    void jwt_short() {
        var env = valid();
        env.setProperty("finsight.security.jwt.secret", "short-secret-value");
        assertProblem(env, "JWT_SECRET");
        assertThat(problems(env)).noneMatch(p -> p.contains("short-secret-value"));
    }

    // ---- database ----

    @ParameterizedTest(name = "url flag={0}")
    @ValueSource(strings = {"useSSL=false", "allowPublicKeyRetrieval=true", "sslMode=DISABLED",
            "sslMode=PREFERRED", "sslMode=REQUIRED", "sslMode=VERIFY_CA", "requireSSL=false"})
    @DisplayName("A TLS-weakening flag in the JDBC URL is refused")
    void dbUrl_weakFlag(String flag) {
        var env = valid();
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/finsight?" + flag);
        assertProblem(env, "TLS-weakening");
    }

    @Test
    @DisplayName("sslMode other than VERIFY_IDENTITY, no truststore URL, or missing DB credentials are refused")
    void dbSettings() {
        var env = valid();
        env.setProperty("spring.datasource.hikari.data-source-properties.sslMode", "VERIFY_CA");
        env.setProperty("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", "file:");
        env.setProperty("spring.datasource.username", "");
        env.setProperty("spring.datasource.password", " ");
        assertProblem(env, "VERIFY_IDENTITY");
        assertProblem(env, "trustCertificateKeyStoreUrl");
        assertProblem(env, "DB_USERNAME");
        assertProblem(env, "DB_PASSWORD");
    }

    @Test
    @DisplayName("An environment variable that is not set is reported by name")
    void unresolvedPlaceholder() {
        var env = valid();
        env.setProperty("spring.datasource.url", "jdbc:mysql://${DB_HOST_NOT_SET}:3306/finsight");
        assertProblem(env, "spring.datasource.url: refers to an environment variable that is not set");
    }

    // ---- truststore ----

    @Test
    @DisplayName("Truststore missing, blank, wrong password, wrong type or not a keystore is refused")
    void truststore_invalid() throws Exception {
        var missing = valid();
        missing.setProperty("finsight.security.db-truststore.path", tls.dir.resolve("absent.p12").toString());
        assertProblem(missing, "file not found");

        var noPath = valid();
        noPath.setProperty("finsight.security.db-truststore.path", "");
        assertProblem(noPath, "DB_TRUSTSTORE_PATH");

        var noPassword = valid();
        noPassword.setProperty("finsight.security.db-truststore.password", "");
        assertProblem(noPassword, "DB_TRUSTSTORE_PASSWORD");

        var wrongPassword = valid();
        wrongPassword.setProperty("finsight.security.db-truststore.password", "wrong-password");
        assertProblem(wrongPassword, "cannot be loaded");

        var wrongType = valid();
        wrongType.setProperty("finsight.security.db-truststore.type", "NOT-A-KEYSTORE-TYPE");
        assertProblem(wrongType, "cannot be loaded");

        var notAStore = valid();
        var junk = Files.writeString(tls.dir.resolve("junk.p12"), "not a keystore");
        notAStore.setProperty("finsight.security.db-truststore.path", junk.toString());
        assertProblem(notAStore, "cannot be loaded");
    }

    // ---- startup hook ----

    @Test
    @DisplayName("The startup hook throws one error listing every problem")
    void postProcess_throws() {
        var env = valid();
        env.setProperty("finsight.cors.allowed-origins", "*");
        env.setProperty("finsight.security.trusted-proxy-count", "2");
        assertThatThrownBy(() -> new ProductionSecurityValidator().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS")
                .hasMessageContaining("TRUSTED_PROXY_COUNT");
    }

    @Test
    @DisplayName("The startup hook does nothing for a valid configuration")
    void postProcess_validPasses() {
        new ProductionSecurityValidator().postProcessEnvironment(valid(), null);
    }
}
