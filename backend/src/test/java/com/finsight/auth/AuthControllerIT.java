package com.finsight.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.finsight.auth.controller.AuthController} (T-16).
 *
 * <p>Covers:
 * <ul>
 *   <li>Register returns 201 with access token and Set-Cookie header.</li>
 *   <li>Login returns 200 with access token and refresh cookie.</li>
 *   <li>Logout returns 204 and clears the cookie (Max-Age=0).</li>
 *   <li>Subsequent refresh with the logout-revoked token returns 401 without
 *       revoking other sessions (AC-AUTH-1).</li>
 *   <li>6th login attempt from the same IP returns 429 (AC-AUTH-5).</li>
 *   <li>Refresh with a spoofed origin returns 403 (AC-CSRF-2).</li>
 *   <li>Refresh with a valid origin succeeds.</li>
 *   <li>Cookie {@code Secure=false} in dev profile (AC-AUTH-7).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_auth_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret",
                () -> "controller-it-secret-must-be-32-chars-long!");
        registry.add("finsight.security.cookie.secure", () -> "false");
        registry.add("finsight.cors.allowed-origins", () -> "http://localhost:5173");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Register returns 201 with access token body and refreshToken Set-Cookie")
    void register_returns201WithTokenAndCookie() throws Exception {
        var request = new RegisterRequest("newuser@example.com", "password123", "New User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")));
    }

    @Test
    @DisplayName("Login returns 200 with access token and sets HttpOnly refresh cookie")
    void login_returnsTokenAndCookie() throws Exception {
        // Register first.
        var reg = new RegisterRequest("login.test@example.com", "password123", "Login Test");
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)));

        var login = new LoginRequest("login.test@example.com", "password123");
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
    }

    @Test
    @DisplayName("Logout returns 204 and clears the cookie with Max-Age=0")
    void logout_returns204AndClearsCookie() throws Exception {
        // Register and get a token.
        var reg = new RegisterRequest("logout.test@example.com", "password123", "Logout Test");
        var regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andReturn();

        var accessToken = objectMapper.readTree(regResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Origin", "http://localhost:5173")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("AC-AUTH-5: 6th login attempt within 15 minutes returns 429")
    void login_sixthAttemptWithin15Min_returns429() throws Exception {
        var login = new LoginRequest("nonexistent@rate-limit.test", "wrong");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("Origin", "http://localhost:5173")
                    // Use a stable IP via forwarded header; MockMvc uses 127.0.0.1 by default.
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(login)));
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("AC-CSRF-2: refresh with spoofed origin returns 403")
    void refresh_spoofedOrigin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "https://evil.example.com")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", "fake")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-AUTH-7: dev profile sets Secure=false on the refresh cookie")
    void register_devProfile_cookieNotSecure() throws Exception {
        var request = new RegisterRequest("devprofile@example.com", "password123", "Dev Test");
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                // In this test context, finsight.security.cookie.secure=false,
                // so the cookie should NOT contain "Secure".
                .andExpect(header().string("Set-Cookie", not(containsString("; Secure"))));
    }

    // ---- A1: Session isolation — logout one session, other remains usable ----

    @Test
    @DisplayName("AC-AUTH-1: logout session 1; session 2 refresh token is still valid")
    void logout_sessionIsolation_otherSessionUnaffected() throws Exception {
        // Register user and capture session-1 tokens.
        var reg = new RegisterRequest("isolation@example.com", "password123", "Isolation Test");
        var regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andReturn();
        var accessToken1 = objectMapper.readTree(
                regResult.getResponse().getContentAsString()).get("accessToken").asText();
        var cookie1 = extractRefreshCookie(regResult.getResponse().getHeader("Set-Cookie"));

        // Session 2: login to get a second independent refresh token.
        var loginReq = new LoginRequest("isolation@example.com", "password123");
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andReturn();
        var cookie2 = extractRefreshCookie(loginResult.getResponse().getHeader("Set-Cookie"));

        // Logout session 1 using its access token.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Origin", "http://localhost:5173")
                        .header("Authorization", "Bearer " + accessToken1)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie1)))
                .andExpect(status().isNoContent());

        // Session 2 refresh cookie should still work — returns 200 with a new access token.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    // ---- A2: Valid origin + valid cookie → 200 and token rotation ----

    @Test
    @DisplayName("AC-CSRF-3: valid allowed Origin + valid refresh cookie returns 200 and rotates token")
    void refresh_validOriginAndCookie_returns200AndNewToken() throws Exception {
        // Register to obtain a refresh token.
        var reg = new RegisterRequest("validorigin@example.com", "password123", "Valid Origin");
        var regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andReturn();
        var originalAccessToken = objectMapper.readTree(
                regResult.getResponse().getContentAsString()).get("accessToken").asText();
        var refreshCookie = extractRefreshCookie(regResult.getResponse().getHeader("Set-Cookie"));

        // Refresh with a valid origin — should return 200, new access token, new cookie.
        var refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=")))
                .andReturn();

        // The new access token must be different from the original (rotation happened).
        var newAccessToken = objectMapper.readTree(
                refreshResult.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(newAccessToken).isNotEqualTo(originalAccessToken);
    }

    // ---- A3: Refresh after logout → 401, no cascade to other sessions ----

    @Test
    @DisplayName("AC-AUTH-1: presenting logout-revoked token to /refresh returns 401 without cascade")
    void refresh_afterLogout_returns401WithoutCascade() throws Exception {
        // Register and immediately create a second session so we can test isolation.
        var reg = new RegisterRequest("postlogout@example.com", "password123", "Post Logout");
        var regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andReturn();
        var accessToken1 = objectMapper.readTree(
                regResult.getResponse().getContentAsString()).get("accessToken").asText();
        var refreshCookie1 = extractRefreshCookie(regResult.getResponse().getHeader("Set-Cookie"));

        // Create session 2 via login.
        var loginReq = new LoginRequest("postlogout@example.com", "password123");
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andReturn();
        var refreshCookie2 = extractRefreshCookie(loginResult.getResponse().getHeader("Set-Cookie"));

        // Logout session 1.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Origin", "http://localhost:5173")
                        .header("Authorization", "Bearer " + accessToken1)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie1)))
                .andExpect(status().isNoContent());

        // Presenting the now-LOGOUT-revoked refresh token returns 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie1)))
                .andExpect(status().isUnauthorized());

        // Session 2 is NOT affected — its token still works (AC-AUTH-1: no cascade on LOGOUT).
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    // ---- Helper ----

    /**
     * Extracts the raw refresh token value from a {@code Set-Cookie} header string.
     * Parses {@code refreshToken=<value>; HttpOnly; ...} and returns {@code <value>}.
     */
    private static String extractRefreshCookie(String setCookieHeader) {
        if (setCookieHeader == null) return "";
        for (var part : setCookieHeader.split(";")) {
            var trimmed = part.trim();
            if (trimmed.startsWith("refreshToken=")) {
                return trimmed.substring("refreshToken=".length());
            }
        }
        return "";
    }
}
