package com.finsight.fraud;

import com.finsight.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP tests for {@code FraudAlertController} on MySQL 8 (T-53): listing, idempotent acknowledge,
 * cross-user 404, paging validation and authentication. Alerts are produced by real transactions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FraudAlertControllerIT {

    private static final String JWT_SECRET = "fraud-alert-controller-it-secret-must-be-at-least-32-chars!";
    private static final AtomicLong USERS = new AtomicLong(13000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_fraud_ctl_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> JWT_SECRET);
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private String bearer(long userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u" + userId + "@test.com", "USER");
    }

    private long idOf(String json) {
        return objectMapper.readTree(json).get("id").asLong();
    }

    private long account(long user) throws Exception {
        var r = mockMvc.perform(post("/api/v1/accounts").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Main\",\"type\":\"CHECKING\",\"openingBalance\":1000.00}"))
                .andExpect(status().isCreated()).andReturn();
        return idOf(r.getResponse().getContentAsString());
    }

    private long tx(long user, long acc, String description, String country) throws Exception {
        var r = mockMvc.perform(post("/api/v1/transactions").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":-12.00,\"transactionDate\":\"2026-03-10\",\"category\":\"OTHER\",\"description\":\"%s\",\"countryCode\":\"%s\"}"
                                .formatted(acc, description, country)))
                .andExpect(status().isCreated()).andReturn();
        return idOf(r.getResponse().getContentAsString());
    }

    /** Creates one UNSEEN_COUNTRY alert for {@code user} and returns its ID. */
    private long unseenCountryAlert(long user) throws Exception {
        long acc = account(user);
        tx(user, acc, "first", "US");
        long second = tx(user, acc, "second", "JP");
        Long id = jdbc.queryForObject("SELECT id FROM fraud_alerts WHERE transaction_id = ?", Long.class, second);
        return id == null ? -1 : id;
    }

    @Test
    @DisplayName("List returns the caller's active alerts with rule, severity and text, and no userId")
    void list_activeAlerts() throws Exception {
        long a = USERS.incrementAndGet();
        long alertId = unseenCountryAlert(a);

        mockMvc.perform(get("/api/v1/fraud/alerts").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value((int) alertId))
                .andExpect(jsonPath("$.content[0].rule").value("UNSEEN_COUNTRY"))
                .andExpect(jsonPath("$.content[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.content[0].acknowledged").value(false))
                .andExpect(jsonPath("$.content[0].description", not(containsString("second"))))
                .andExpect(jsonPath("$..userId").doesNotExist());
    }

    @Test
    @DisplayName("Acknowledge returns 200, repeats safely with the same time, and removes the alert from the list")
    void acknowledge_idempotent() throws Exception {
        long a = USERS.incrementAndGet();
        long alertId = unseenCountryAlert(a);

        var first = mockMvc.perform(patch("/api/v1/fraud/alerts/{id}/acknowledge", alertId).header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty())
                .andReturn();
        var firstAt = objectMapper.readTree(first.getResponse().getContentAsString()).get("acknowledgedAt").asString();

        mockMvc.perform(patch("/api/v1/fraud/alerts/{id}/acknowledge", alertId).header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(firstAt));
        mockMvc.perform(get("/api/v1/fraud/alerts").header("Authorization", bearer(a)))
                .andExpect(jsonPath("$.content", empty()));
        assertThat(jdbc.queryForObject("SELECT acknowledged FROM fraud_alerts WHERE id = ?", Boolean.class, alertId)).isTrue();
    }

    @Test
    @DisplayName("User B gets 404 acknowledging A's alert, sees none of A's alerts, and A's alert is unchanged")
    void otherUser_404() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long alertId = unseenCountryAlert(a);

        mockMvc.perform(patch("/api/v1/fraud/alerts/{id}/acknowledge", alertId).header("Authorization", bearer(b)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/v1/fraud/alerts").header("Authorization", bearer(b)))
                .andExpect(jsonPath("$.content", empty()));
        assertThat(jdbc.queryForObject("SELECT acknowledged FROM fraud_alerts WHERE id = ?", Boolean.class, alertId)).isFalse();
    }

    @Test
    @DisplayName("Invalid paging returns 400 ProblemDetail")
    void list_invalidPaging_400() throws Exception {
        long a = USERS.incrementAndGet();
        mockMvc.perform(get("/api/v1/fraud/alerts").param("page", "-1").header("Authorization", bearer(a)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mockMvc.perform(get("/api/v1/fraud/alerts").param("size", "0").header("Authorization", bearer(a)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("No token returns 401 on both fraud endpoints")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/fraud/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/fraud/alerts/1/acknowledge")).andExpect(status().isUnauthorized());
    }
}
