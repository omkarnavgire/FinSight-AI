package com.finsight.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTPS enforcement behind a TRUSTED proxy, on a real embedded Tomcat (T-63, Q-4). The test client
 * connects from 127.0.0.1, which the configured proxy regex trusts, so Tomcat's RemoteIpValve
 * applies its X-Forwarded-* headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "finsight.security.jwt.secret=trusted-proxy-test-secret-at-least-32-bytes!",
        "finsight.security.require-https=true",
        "finsight.security.public-host=api.finsight.example",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1",
        "server.tomcat.remoteip.trusted-proxies=",
        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For",
        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto",
        "finsight.cors.allowed-origins=http://localhost:5173"
})
class TrustedProxyHttpsTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String forwardedFor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Plain HTTP from the proxy without X-Forwarded-Proto is redirected to https (308)")
    void plainHttp_redirected() throws Exception {
        var response = get("/api/v1/accounts");

        assertThat(response.statusCode()).isEqualTo(308);
        assertThat(response.headers().firstValue("Location")).hasValue("https://api.finsight.example/api/v1/accounts");
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    @DisplayName("X-Forwarded-Proto: https from the trusted proxy reaches the app and gets HSTS")
    void trustedHttps_servedWithHsts() throws Exception {
        var response = get("/api/v1/accounts", "X-Forwarded-Proto", "https");

        assertThat(response.statusCode()).as("reaches security: unauthenticated").isEqualTo(401);
        assertThat(response.headers().firstValue("Strict-Transport-Security"))
                .hasValue("max-age=31536000 ; includeSubDomains");
    }

    @Test
    @DisplayName("The health probe is reachable over plain HTTP")
    void health_plainHttp() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Client IP comes from X-Forwarded-For set by the trusted proxy: limits are per real client")
    void trustedForwardedFor_usedForRateLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("198.51.100.20").statusCode()).isEqualTo(401);
        }
        assertThat(login("198.51.100.20").statusCode()).as("6th attempt from the same client").isEqualTo(429);
        assertThat(login("198.51.100.21").statusCode()).as("a different client is not blocked").isEqualTo(401);
    }
}
