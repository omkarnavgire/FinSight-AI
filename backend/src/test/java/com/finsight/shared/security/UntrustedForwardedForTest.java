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
 * A client-supplied X-Forwarded-For from an UNTRUSTED address cannot dodge the login rate limit
 * (T-63, Q-4). HTTPS enforcement is off here only so the login endpoint is reachable over the
 * plain test connection; the proxy regex trusts only 10.9.9.9.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "finsight.security.jwt.secret=untrusted-xff-test-secret-at-least-32-bytes!",
        "finsight.security.require-https=false",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9",
        "server.tomcat.remoteip.trusted-proxies=",
        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For",
        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto",
        "finsight.cors.allowed-origins=http://localhost:5173"
})
class UntrustedForwardedForTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> login(String spoofedForwardedFor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .header("X-Forwarded-For", spoofedForwardedFor)
                .header("X-Forwarded-Proto", "https")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Rotating spoofed X-Forwarded-For values still counts every attempt against 127.0.0.1")
    void spoofedForwardedFor_ignored() throws Exception {
        for (int i = 1; i <= 5; i++) {
            assertThat(login("198.51.100." + i).statusCode()).isEqualTo(401);
        }
        assertThat(login("198.51.100.6").statusCode()).as("6th attempt from the real address").isEqualTo(429);
    }

    @Test
    @DisplayName("A spoofed X-Forwarded-Proto does not make the request secure: no HSTS header")
    void spoofedProto_noHsts() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .header("X-Forwarded-Proto", "https").GET().build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
}
