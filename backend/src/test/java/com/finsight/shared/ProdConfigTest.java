package com.finsight.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Static checks of the packaged configuration files (Q-3, T-62, T-63). No application context. */
class ProdConfigTest {

    private static Properties yaml(String name) {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        var props = factory.getObject();
        assertThat(props).as(name).isNotNull();
        return props;
    }

    private static String text(String name) throws IOException {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("application-prod.yml enforces HTTPS, secure cookies, native forwarded headers and proxy count 0")
    void prod_transportSettings() {
        var p = yaml("application-prod.yml");

        assertThat(p.getProperty("finsight.security.require-https")).isEqualTo("true");
        assertThat(p.getProperty("finsight.security.cookie.secure")).isEqualTo("true");
        assertThat(p.getProperty("finsight.security.trusted-proxy-count")).isEqualTo("0");
        assertThat(p.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(p.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo("${TRUSTED_PROXY_REGEX:}");
        assertThat(p.getProperty("server.tomcat.remoteip.trusted-proxies")).isEmpty();
    }

    @Test
    @DisplayName("application-prod.yml requires verified MySQL TLS and has no weakening flags")
    void prod_databaseTls() throws IOException {
        var p = yaml("application-prod.yml");

        assertThat(p.getProperty("spring.datasource.hikari.data-source-properties.sslMode")).isEqualTo("VERIFY_IDENTITY");
        assertThat(p.getProperty("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl"))
                .isEqualTo("file:${DB_TRUSTSTORE_PATH:}");
        assertThat(text("application-prod.yml").toLowerCase(Locale.ROOT))
                .doesNotContain("usessl=false").doesNotContain("allowpublickeyretrieval");
    }

    @Test
    @DisplayName("CORS has no default origin in base or prod, and no wildcard anywhere")
    void cors_noDefaultOrWildcard() {
        assertThat(yaml("application.yml").getProperty("finsight.cors.allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:}");
        assertThat(yaml("application-prod.yml").getProperty("finsight.cors.allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:}");
        for (var file : new String[]{"application.yml", "application-prod.yml", "application-dev.yml"}) {
            assertThat(yaml(file).getProperty("finsight.cors.allowed-origins", "")).as(file).doesNotContain("*");
        }
    }

    @Test
    @DisplayName("The base configuration fails closed: HTTPS required, forwarded headers not trusted, no insecure DB URL")
    void base_failsClosed() throws IOException {
        var p = yaml("application.yml");

        assertThat(p.getProperty("finsight.security.require-https")).isEqualTo("true");
        assertThat(p.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(p.getProperty("spring.datasource.url").toLowerCase(Locale.ROOT))
                .doesNotContain("usessl=false").doesNotContain("allowpublickeyretrieval");
    }

    @Test
    @DisplayName("Only the dev profile turns HTTPS off and allows the local insecure DB URL")
    void dev_isTheOnlyLocalProfile() {
        var dev = yaml("application-dev.yml");

        assertThat(dev.getProperty("finsight.security.require-https")).isEqualTo("false");
        assertThat(dev.getProperty("finsight.seed.demo-password")).isEqualTo("${FINSIGHT_DEMO_PASSWORD:}");
    }

    @Test
    @DisplayName("No packaged configuration contains a demo credential or a literal secret")
    void noSecretsInConfig() throws IOException {
        for (var file : new String[]{"application.yml", "application-prod.yml", "application-dev.yml"}) {
            var content = text(file);
            assertThat(content).as(file).doesNotContain("Demo@12345").doesNotContain("demo@finsight.dev");
            assertThat(yaml(file).getProperty("finsight.security.jwt.secret", "${JWT_SECRET}"))
                    .as(file).isEqualTo("${JWT_SECRET}");
        }
    }
}
