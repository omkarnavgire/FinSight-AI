package com.finsight.devdata;

import com.finsight.FinSightApplication;
import com.finsight.support.TestTls;
import com.finsight.support.TlsMySqlContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-61b (MySQL half) and AC-SEED-2: the real application starts under {@code prod} against MySQL
 * with verified TLS ({@code VERIFY_IDENTITY}), passes every startup security check, has no seeder,
 * and leaves {@code users} empty.
 *
 * <p>Started with command-line arguments rather than {@code @SpringBootTest}: the startup checks
 * run before test property sources are added, exactly as in a real deployment.
 */
class DataSeederProfileIT {

    private static TestTls tls;
    private static MySQLContainer<?> mysql;
    private static ConfigurableApplicationContext app;

    @BeforeAll
    static void startProd() {
        tls = TestTls.create();
        mysql = TlsMySqlContainer.create(tls, "finsight_prod_it");
        mysql.start();
        app = new SpringApplicationBuilder(FinSightApplication.class)
                .profiles("prod")
                .run("--server.port=0",
                        "--spring.datasource.url=" + TlsMySqlContainer.url(mysql, "localhost"),
                        "--spring.datasource.username=" + mysql.getUsername(),
                        "--spring.datasource.password=" + mysql.getPassword(),
                        "--spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl="
                                + tls.truststore.toUri(),
                        "--spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword="
                                + TestTls.STORE_PASSWORD,
                        "--finsight.security.db-truststore.path=" + tls.truststore,
                        "--finsight.security.db-truststore.password=" + TestTls.STORE_PASSWORD,
                        "--finsight.security.jwt.secret=prod-profile-it-secret-at-least-32-bytes!",
                        "--finsight.cors.allowed-origins=https://app.finsight.example",
                        "--finsight.security.public-host=api.finsight.example",
                        "--server.tomcat.remoteip.internal-proxies=10\\.0\\.0\\.10");
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    @DisplayName("prod starts against verified-TLS MySQL; no DataSeeder bean; users stays empty")
    void prod_noSeeder_noUsers() {
        var jdbc = app.getBean(JdbcTemplate.class);

        assertThat(app.getBeanNamesForType(DataSeeder.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class,
                DataSeeder.DEMO_EMAIL)).isZero();
    }

    @Test
    @DisplayName("The application's pooled connections really use TLS")
    void pooledConnection_usesTls() {
        var jdbc = app.getBean(JdbcTemplate.class);

        var cipher = jdbc.queryForList("SHOW SESSION STATUS LIKE 'Ssl_cipher'").getFirst().get("Value");
        assertThat(String.valueOf(cipher)).isNotBlank();
    }
}
