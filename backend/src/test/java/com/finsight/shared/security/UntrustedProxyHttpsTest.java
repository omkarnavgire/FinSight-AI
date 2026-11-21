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
 * Spoofed forwarded headers from an UNTRUSTED address, on a real embedded Tomcat (T-63, Q-4).
 * The proxy regex trusts only 10.9.9.9, so the test client at 127.0.0.1 is an ordinary client and
 * its X-Forwarded-Proto / X-Forwarded-For must be ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "finsight.security.jwt.secret=untrusted-proxy-test-secret-at-least-32-bytes",
        "finsight.security.require-https=true",
        "finsight.security.public-host=api.finsight.example",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9",
        "server.tomcat.remoteip.trusted-proxies=",
        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For",
        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto",
        "finsight.cors.allowed-origins=https://app.finsight.example"
})
class UntrustedProxyHttpsTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    @Test
    @DisplayName("A spoofed X-Forwarded-Proto: https from an untrusted client is ignored: still redirected, no HSTS")
    void spoofedProto_ignored() throws Exception {
        var response = send(request("/api/v1/accounts").header("X-Forwarded-Proto", "https").GET());

        assertThat(response.statusCode()).isEqualTo(308);
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    @DisplayName("A spoofed X-Forwarded-For does not change the redirect target host or bypass enforcement")
    void spoofedFor_doesNotBypass() throws Exception {
        var response = send(request("/api/v1/auth/login")
                .header("X-Forwarded-For", "198.51.100.99")
                .header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(308);
        assertThat(response.headers().firstValue("Location"))
                .hasValue("https://api.finsight.example/api/v1/auth/login");
    }
}
