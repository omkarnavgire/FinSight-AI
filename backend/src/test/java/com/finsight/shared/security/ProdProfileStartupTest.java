package com.finsight.shared.security;

import com.finsight.FinSightApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The real application refuses to start under {@code prod} when security settings are missing or
 * unsafe (Q-4). The checks run before any bean or database connection exists, so no database or
 * Docker is needed.
 */
class ProdProfileStartupTest {

    private static Throwable startProd(String... args) {
        return catchThrowable(() -> new SpringApplicationBuilder(FinSightApplication.class)
                .profiles("prod")
                .run(args)
                .close());
    }

    private static String fullMessage(Throwable t) {
        var sb = new StringBuilder();
        for (var c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("prod with no security environment fails at startup and names every missing variable")
    void missingEverything_fails() {
        var error = startProd("--server.port=0");

        assertThat(error).isNotNull();
        assertThat(fullMessage(error)).contains("Insecure production configuration")
                .contains("CORS_ALLOWED_ORIGINS")
                .contains("TRUSTED_PROXY_REGEX")
                .contains("PUBLIC_HOST")
                .contains("JWT_SECRET")
                .contains("DB_TRUSTSTORE_PATH");
    }

    @Test
    @DisplayName("Error messages never contain the configured values")
    void valuesNotLeaked() {
        var error = startProd("--server.port=0",
                "--finsight.security.jwt.secret=short-leaky-jwt",
                "--CORS_ALLOWED_ORIGINS=http://leaky-origin.example",
                "--DB_PASSWORD=leaky-db-password");

        assertThat(fullMessage(error)).doesNotContain("short-leaky-jwt")
                .doesNotContain("leaky-origin")
                .doesNotContain("leaky-db-password");
    }

    @Test
    @DisplayName("prod combined with dev is refused at startup")
    void prodPlusDev_fails() {
        var error = catchThrowable(() -> new SpringApplicationBuilder(FinSightApplication.class)
                .profiles("prod", "dev")
                .run("--server.port=0")
                .close());

        assertThat(fullMessage(error)).contains("must not be combined");
    }
}
