package com.finsight.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link HttpsEnforcementFilter} (T-63, NFR-01-2, M-1). */
class HttpsEnforcementFilterTest {

    private final HttpsEnforcementFilter filter = new HttpsEnforcementFilter("api.finsight.example");

    private static MockHttpServletRequest request(String method, String uri, boolean secure) {
        var r = new MockHttpServletRequest(method, uri);
        r.setServerName("evil.example");   // client-controlled Host header: must never be used
        r.setSecure(secure);
        return r;
    }

    @Test
    @DisplayName("Plain HTTP is redirected with 308 to the configured host, keeping path and query")
    void plainHttp_redirected() throws Exception {
        var req = request("GET", "/api/v1/accounts", false);
        req.setQueryString("page=1&size=5");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(308);
        assertThat(res.getHeader("Location")).isEqualTo("https://api.finsight.example/api/v1/accounts?page=1&size=5");
        assertThat(chain.getRequest()).as("request must not reach the app").isNull();
    }

    @Test
    @DisplayName("M-1: a spoofed Host header never becomes the redirect target")
    void spoofedHost_ignored() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/x", false), res, new MockFilterChain());

        assertThat(res.getHeader("Location")).startsWith("https://api.finsight.example/").doesNotContain("evil");
    }

    @Test
    @DisplayName("A configured host with a port is kept; the host is lower-cased")
    void hostWithPort() throws Exception {
        var res = new MockHttpServletResponse();
        new HttpsEnforcementFilter(" API.Finsight.example:8443 ").doFilter(request("GET", "/x", false), res, new MockFilterChain());

        assertThat(res.getHeader("Location")).isEqualTo("https://api.finsight.example:8443/x");
    }

    @ParameterizedTest(name = "publicHost=[{0}]")
    @ValueSource(strings = {"", " ", "https://api.finsight.example", "api.finsight.example/path", "*.finsight.example", "a b"})
    @DisplayName("M-1: without a valid public host, plain HTTP is refused with 400 and no Location")
    void invalidPublicHost_refused(String host) throws Exception {
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new HttpsEnforcementFilter(host).doFilter(request("GET", "/api/v1/accounts", false), res, chain);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getHeader("Location")).isNull();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("A POST is redirected with 308, so clients keep the method and body")
    void post_redirectedWith308() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/v1/auth/login", false), res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(308);
    }

    @Test
    @DisplayName("A secure request passes through")
    void secure_passes() throws Exception {
        var chain = new MockFilterChain();
        var res = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/accounts", true), res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getHeader("Location")).isNull();
    }

    @Test
    @DisplayName("The health probe passes over plain HTTP; other actuator paths do not")
    void health_exempt() throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(request("GET", "/actuator/health", false), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();

        var res = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/actuator/info", false), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(308);
    }
}
