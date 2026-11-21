package com.finsight.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans production sources and resources for committed secrets (Q-4: no production secrets or
 * demo credentials in source control). Runs from the {@code backend} module directory.
 */
class SourceSecretsScanTest {

    private static final Path MAIN = Path.of("src", "main");

    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern AWS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern HARDCODED_SECRET = Pattern.compile(
            "(?i)(password|secret|api[_-]?key)\\s*[:=]\\s*[\"']?(?!\\$\\{)[A-Za-z0-9@#!%^&*+/=_\\-]{8,}");

    private static ArrayList<Path> files() throws IOException {
        var result = new ArrayList<Path>();
        try (var walk = Files.walk(MAIN)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> { var n = p.toString(); return n.endsWith(".java") || n.endsWith(".yml") || n.endsWith(".properties"); })
                    .forEach(result::add);
        }
        return result;
    }

    @Test
    @DisplayName("Production sources contain no private keys, cloud keys, demo password or hard-coded secrets")
    void noSecrets() throws IOException {
        var findings = new ArrayList<String>();
        for (var file : files()) {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (PRIVATE_KEY.matcher(content).find()) {
                findings.add(file + ": private key");
            }
            if (AWS_KEY.matcher(content).find()) {
                findings.add(file + ": cloud access key");
            }
            if (content.contains("Demo@12345")) {
                findings.add(file + ": demo password");
            }
            if (file.toString().endsWith(".yml") && HARDCODED_SECRET.matcher(content).find()) {
                findings.add(file + ": hard-coded secret value");
            }
        }
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("TLS-weakening JDBC flags appear in no configuration file except application-dev.yml")
    void insecureJdbcFlags_devOnly() throws IOException {
        var findings = new ArrayList<String>();
        for (var file : files()) {
            var name = file.getFileName().toString();
            if (name.equals("application-dev.yml") || name.endsWith(".java")) {
                continue;
            }
            var content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (content.contains("usessl=false") || content.contains("allowpublickeyretrieval=true")) {
                findings.add(file.toString());
            }
        }
        assertThat(findings).isEmpty();
    }
}
