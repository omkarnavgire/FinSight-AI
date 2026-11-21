package com.finsight.shared.security;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stops application startup when a non-local deployment is not configured securely (T-63, Q-4).
 *
 * <h2>When it runs</h2>
 * As an {@link EnvironmentPostProcessor}, after all configuration files are loaded and before any
 * bean or database connection exists. Fail closed (H-1): every deployment is enforced except the
 * {@code dev} profile, and {@code dev} itself may only use a database on localhost. The {@code test}
 * profile and {@code require-https=false} skip the checks only inside a test JVM.
 *
 * <h2>Output</h2>
 * All problems are collected and reported in one {@link IllegalStateException}. Messages name the
 * environment variable and property, never their values.
 */
public class ProductionSecurityValidator implements EnvironmentPostProcessor, Ordered {

    static final String PROD = "prod";
    static final String DEV = "dev";
    static final String TEST = "test";
    static final int MIN_JWT_SECRET_BYTES = 32;
    /** Exists only on the test classpath, never in the packaged app, so tests cannot open a bypass (H-1). */
    static final String TEST_RUNTIME_MARKER = "org.junit.platform.commons.annotation.Testable";
    /** Public addresses a proxy regex must never match: a regex that does is far too broad. */
    static final List<String> PUBLIC_PROBES = List.of("8.8.8.8", "1.1.1.1", "203.0.113.7",
            "2001:4860:4860::8888", "2001:4860:4860:0:0:0:0:8888");
    /** {@code dev} may run only against a database on the developer's own machine (H-1). */
    static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Pattern WEAK_DB_FLAGS = Pattern.compile(
            "(?i)(usessl=false|requiressl=false|allowpublickeyretrieval=true|sslmode=(disabled|preferred|required|verify_ca))");
    private static final Pattern JDBC_HOST = Pattern.compile("^jdbc:mysql://(\\[[^\\]]+\\]|[^:/?]+)");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var problems = problems(environment, Set.of(environment.getActiveProfiles()));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Insecure production configuration; startup stopped:\n - "
                    + String.join("\n - ", problems));
        }
    }

    /** {@code true} when running inside a test JVM (JUnit on the classpath), never in the packaged app. */
    static boolean isTestRuntime() {
        return ClassUtils.isPresent(TEST_RUNTIME_MARKER, ProductionSecurityValidator.class.getClassLoader());
    }

    /**
     * {@code true} when the checks apply (H-1). Only {@code dev} is a local profile in the application.
     * The {@code test} profile and {@code require-https=false} count as local only inside a test JVM;
     * in the packaged application they are enforced like any other deployment.
     */
    static boolean isEnforced(Environment env, Set<String> profiles, boolean testRuntime) {
        if (profiles.contains(PROD)) {
            return true;
        }
        if (profiles.contains(DEV)) {
            return false;
        }
        if (testRuntime) {
            var requireHttps = readBoolean(env, "finsight.security.require-https", true);
            return !profiles.contains(TEST) && requireHttps;
        }
        return true;
    }

    /** All problems, detecting whether this is a test JVM. */
    static List<String> problems(Environment env, Set<String> profiles) {
        return problems(env, profiles, isTestRuntime());
    }

    /**
     * All configuration problems for these profiles; empty when the configuration is acceptable
     * or the checks do not apply.
     *
     * @param testRuntime {@code true} only inside a test JVM
     */
    static List<String> problems(Environment env, Set<String> profiles, boolean testRuntime) {
        var problems = new ArrayList<String>();
        if (profiles.contains(PROD) && (profiles.contains(DEV) || profiles.contains(TEST))) {
            problems.add("profiles: 'prod' must not be combined with a local profile (dev, test)");
        }
        if (profiles.contains(DEV) && !profiles.contains(PROD) && !testRuntime) {
            checkDevIsLocal(env, problems);
        }
        if (!isEnforced(env, profiles, testRuntime)) {
            return problems;
        }
        requireTrue(env, problems, "finsight.security.require-https", "FINSIGHT_SECURITY_REQUIRE_HTTPS",
                "HTTPS enforcement must be on outside the dev profile");
        requireTrue(env, problems, "finsight.security.cookie.secure", "COOKIE_SECURE", "refresh cookie must be Secure");
        checkPublicHost(env, problems);
        checkCors(env, problems);
        checkProxy(env, problems);
        checkJwt(env, problems);
        checkDatabase(env, problems);
        checkTruststore(env, problems);
        return problems;
    }

    // ---- individual checks ----

    /** The dev profile (no TLS, plain HTTP) may only point at a database on this machine (H-1). */
    private static void checkDevIsLocal(Environment env, List<String> problems) {
        var url = read(env, "spring.datasource.url", problems);
        var matcher = url == null ? null : JDBC_HOST.matcher(url);
        if (matcher == null || !matcher.find()
                || !LOOPBACK_HOSTS.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
            problems.add("DB_HOST (spring.datasource.url): the dev profile may only use a database on localhost;"
                    + " use the prod profile for any other host");
        }
    }

    /** M-1: the HTTPS redirect target comes from configuration, never from the request's Host header. */
    private static void checkPublicHost(Environment env, List<String> problems) {
        if (!SecuritySettingRules.isValidPublicHost(read(env, "finsight.security.public-host", problems))) {
            problems.add("PUBLIC_HOST (finsight.security.public-host): required public host name"
                    + " (host or host:port, no scheme or path)");
        }
    }

    private static void checkCors(Environment env, List<String> problems) {
        var raw = read(env, "finsight.cors.allowed-origins", problems);
        var label = "CORS_ALLOWED_ORIGINS (finsight.cors.allowed-origins)";
        if (raw == null) {
            return;
        }
        var origins = Arrays.stream(raw.split(",", -1)).map(String::trim).toList();
        if (origins.isEmpty() || origins.stream().allMatch(String::isEmpty)) {
            problems.add(label + ": required, at least one exact https origin");
            return;
        }
        for (int i = 0; i < origins.size(); i++) {
            if (!SecuritySettingRules.isExactHttpsOrigin(origins.get(i))) {
                problems.add(label + ": entry " + (i + 1) + " must be an exact https origin (https://host[:port], no wildcard or path)");
            }
        }
    }

    private static void checkProxy(Environment env, List<String> problems) {
        var strategy = read(env, "server.forward-headers-strategy", problems);
        if (strategy == null || !"native".equalsIgnoreCase(strategy.trim())) {
            problems.add("server.forward-headers-strategy: must be 'native' (TLS ends at the trusted proxy)");
        }
        var label = "TRUSTED_PROXY_REGEX (server.tomcat.remoteip.internal-proxies)";
        var regex = read(env, "server.tomcat.remoteip.internal-proxies", problems);
        if (regex == null || regex.isBlank()) {
            problems.add(label + ": required");
        } else if (!SecuritySettingRules.isExactAddressList(regex)) {
            problems.add(label + ": must list exact proxy addresses only, e.g. 10\\.0\\.0\\.10|10\\.0\\.0\\.11"
                    + " (no ranges or wildcards)");
        } else {
            try {
                var pattern = Pattern.compile(regex);
                if (PUBLIC_PROBES.stream().anyMatch(ip -> pattern.matcher(ip).matches())) {
                    problems.add(label + ": too broad, it matches public addresses");
                }
            } catch (PatternSyntaxException e) {
                problems.add(label + ": not a valid regular expression");
            }
        }
        var trustedProxies = read(env, "server.tomcat.remoteip.trusted-proxies", problems);
        if (trustedProxies != null && !trustedProxies.isBlank()) {
            problems.add("server.tomcat.remoteip.trusted-proxies: must be empty; only TRUSTED_PROXY_REGEX is trusted");
        }
        var count = read(env, "finsight.security.trusted-proxy-count", problems);
        if (count != null && !"0".equals(count.trim())) {
            problems.add("TRUSTED_PROXY_COUNT (finsight.security.trusted-proxy-count): must be 0 behind the trusted proxy");
        }
    }

    private static void checkJwt(Environment env, List<String> problems) {
        var secret = read(env, "finsight.security.jwt.secret", problems);
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("JWT_SECRET (finsight.security.jwt.secret): required, at least " + MIN_JWT_SECRET_BYTES + " bytes");
        }
    }

    private static void checkDatabase(Environment env, List<String> problems) {
        var url = read(env, "spring.datasource.url", problems);
        if (url == null || url.isBlank() || !url.startsWith("jdbc:mysql://")) {
            problems.add("DB_HOST / DB_NAME (spring.datasource.url): required MySQL JDBC URL");
        } else if (WEAK_DB_FLAGS.matcher(url).find()) {
            problems.add("spring.datasource.url: contains a TLS-weakening flag");
        }
        requireText(env, problems, "spring.datasource.username", "DB_USERNAME");
        requireText(env, problems, "spring.datasource.password", "DB_PASSWORD");
        var base = "spring.datasource.hikari.data-source-properties.";
        var sslMode = read(env, base + "sslMode", problems);
        if (sslMode == null || !"VERIFY_IDENTITY".equals(sslMode.trim().toUpperCase(Locale.ROOT))) {
            problems.add(base + "sslMode: must be VERIFY_IDENTITY (certificate and hostname verification)");
        }
        var trustUrl = read(env, base + "trustCertificateKeyStoreUrl", problems);
        if (trustUrl == null || !trustUrl.startsWith("file:") || trustUrl.length() <= "file:".length()) {
            problems.add(base + "trustCertificateKeyStoreUrl: must point to the DB_TRUSTSTORE_PATH file");
        }
    }

    private static void checkTruststore(Environment env, List<String> problems) {
        var label = "DB_TRUSTSTORE_PATH (finsight.security.db-truststore.path)";
        var path = read(env, "finsight.security.db-truststore.path", problems);
        var password = read(env, "finsight.security.db-truststore.password", problems);
        var type = read(env, "finsight.security.db-truststore.type", problems);
        if (path == null || path.isBlank()) {
            problems.add(label + ": required");
            return;
        }
        if (password == null || password.isEmpty()) {
            problems.add("DB_TRUSTSTORE_PASSWORD (finsight.security.db-truststore.password): required");
            return;
        }
        var file = Path.of(path);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            problems.add(label + ": file not found or not readable");
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            var store = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type.trim());
            store.load(in, password.toCharArray());
            int valid = 0;
            for (var alias : Collections.list(store.aliases())) {
                if (store.getCertificate(alias) instanceof X509Certificate cert) {
                    try {
                        cert.checkValidity();
                        valid++;
                    } catch (Exception expired) {
                        // counted below
                    }
                }
            }
            if (valid == 0) {
                problems.add(label + ": contains no valid (unexpired) certificate");
            }
        } catch (Exception e) {
            problems.add(label + ": cannot be loaded with DB_TRUSTSTORE_PASSWORD and DB_TRUSTSTORE_TYPE");
        }
    }

    // ---- helpers ----

    /** Reads a property; an unresolvable placeholder is reported without its value. */
    private static String read(Environment env, String key, List<String> problems) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException unresolved) {
            problems.add(key + ": refers to an environment variable that is not set");
            return null;
        }
    }

    private static boolean readBoolean(Environment env, String key, boolean fallback) {
        try {
            var value = env.getProperty(key);
            return value == null ? fallback : Boolean.parseBoolean(value.trim());
        } catch (IllegalArgumentException unresolved) {
            return fallback;
        }
    }

    private static void requireTrue(Environment env, List<String> problems, String key, String envVar, String reason) {
        var value = read(env, key, problems);
        if (value == null || !Boolean.parseBoolean(value.trim())) {
            problems.add(envVar + " (" + key + "): " + reason);
        }
    }

    private static void requireText(Environment env, List<String> problems, String key, String envVar) {
        var value = read(env, key, problems);
        if (value == null || value.isBlank()) {
            problems.add(envVar + " (" + key + "): required");
        }
    }
}
