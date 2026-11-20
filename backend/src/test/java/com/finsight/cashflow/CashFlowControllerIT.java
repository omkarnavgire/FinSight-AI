package com.finsight.cashflow;

import com.finsight.auth.service.JwtService;
import com.finsight.cashflow.repository.CashFlowSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP tests for {@code CashFlowController} on MySQL 8 (T-47): refresh rate limit (429 + Retry-After),
 * snapshot creation and reuse, horizons, validation, ownership and authentication. "Today" is fixed
 * at 2026-03-15 by a {@code @Primary} test clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CashFlowControllerIT {

    private static final String JWT_SECRET = "cashflow-controller-it-secret-must-be-at-least-32-chars!";
    private static final AtomicLong USERS = new AtomicLong(9000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_cashflow_ctl_it")
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

    /** Replaces the system clock in this class's context only. Production clock configuration is unchanged. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CashFlowSnapshotRepository snapshotRepository;

    private String bearer(long userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u" + userId + "@test.com", "USER");
    }

    private long account(long user, String opening) throws Exception {
        var r = mockMvc.perform(post("/api/v1/accounts").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Main\",\"type\":\"CHECKING\",\"openingBalance\":%s}".formatted(opening)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private void tx(long user, long acc, String amount, String date) throws Exception {
        mockMvc.perform(post("/api/v1/transactions").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":%s,\"transactionDate\":\"%s\",\"category\":\"UTILITIES\",\"countryCode\":\"US\"}"
                                .formatted(acc, amount, date)))
                .andExpect(status().isCreated());
    }

    private int snapshotRows(long user) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM cash_flow_snapshots WHERE user_id = ?", Integer.class, user);
        return n == null ? 0 : n;
    }

    // ---- refresh rate limit (T-47) ----

    @Test
    @DisplayName("T-47: a second refresh within one hour returns 429 with Retry-After")
    void refresh_secondCallWithinHour_429() throws Exception {
        long a = USERS.incrementAndGet();

        mockMvc.perform(post("/api/v1/cashflow/refresh").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].horizonDays", contains(30, 60, 90)));

        var r = mockMvc.perform(post("/api/v1/cashflow/refresh").header("Authorization", bearer(a)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andReturn();
        assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(snapshotRows(a)).isEqualTo(3);
    }

    @Test
    @DisplayName("The refresh limit is per user: another user can still refresh")
    void refresh_limitIsPerUser() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        mockMvc.perform(post("/api/v1/cashflow/refresh").header("Authorization", bearer(a))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cashflow/refresh").header("Authorization", bearer(b))).andExpect(status().isOk());
    }

    // ---- GET ----

    @Test
    @DisplayName("First GET builds and stores all three horizons, returns 30 days by default; a second GET reuses them")
    void get_default_buildsThenReuses() throws Exception {
        long a = USERS.incrementAndGet();

        var first = mockMvc.perform(get("/api/v1/cashflow/forecast").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizonDays").value(30))
                .andExpect(jsonPath("$.asOf").value("2026-03-15"))
                .andExpect(jsonPath("$.startingBalance").value(0.00))
                .andExpect(jsonPath("$.from").value("2026-03-16"))
                .andExpect(jsonPath("$.to").value("2026-04-14"))
                .andExpect(jsonPath("$.days", hasSize(30)))
                .andExpect(jsonPath("$.lowConfidence").value(true))
                .andExpect(jsonPath("$.reason").value("NO_HISTORY"))
                .andExpect(jsonPath("$..userId").doesNotExist())
                .andReturn();
        assertThat(snapshotRows(a)).isEqualTo(3);
        var generatedAt = objectMapper.readTree(first.getResponse().getContentAsString()).get("generatedAt").asString();

        mockMvc.perform(get("/api/v1/cashflow/forecast").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value(generatedAt));
        assertThat(snapshotRows(a)).isEqualTo(3);
    }

    @Test
    @DisplayName("horizon=60 and horizon=90 return 60 and 90 daily points")
    void get_otherHorizons() throws Exception {
        long a = USERS.incrementAndGet();
        for (var h : new int[]{60, 90}) {
            mockMvc.perform(get("/api/v1/cashflow/forecast").param("horizon", String.valueOf(h))
                            .header("Authorization", bearer(a)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.horizonDays").value(h))
                    .andExpect(jsonPath("$.days", hasSize(h)));
        }
    }

    @Test
    @DisplayName("A recurring monthly bill is projected from the stored snapshot (JSON column round-trip)")
    void get_recurringProjected() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a, "1000.00");
        tx(a, acc, "-100.00", "2026-01-10");
        tx(a, acc, "-100.00", "2026-02-10");   // balance now 800.00

        mockMvc.perform(post("/api/v1/cashflow/refresh").header("Authorization", bearer(a))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cashflow/forecast").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowConfidence").value(false))
                .andExpect(jsonPath("$.startingBalance").value(800.00))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.days[0].projectedBalance").value(800.00))
                .andExpect(jsonPath("$.days[25].date").value("2026-04-10"))
                .andExpect(jsonPath("$.days[25].projectedBalance").value(700.00))
                .andExpect(jsonPath("$.projectedEndBalance").value(700.00));
    }

    // ---- ownership ----

    @Test
    @DisplayName("User B's forecast never includes user A's accounts or transactions")
    void isolation_betweenUsers() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long accA = account(a, "5000.00");
        tx(a, accA, "-100.00", "2026-01-10");
        tx(a, accA, "-100.00", "2026-02-10");

        mockMvc.perform(get("/api/v1/cashflow/forecast").header("Authorization", bearer(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectedEndBalance").value(0.00))
                .andExpect(jsonPath("$.reason").value("NO_HISTORY"));
        assertThat(snapshotRows(a)).isZero();
        assertThat(snapshotRows(b)).isEqualTo(3);
    }

    @Test
    @DisplayName("T-18: findByIdAndUserId returns the owner's snapshot and empty for another user")
    void repository_findByIdAndUserId_ownerScoped() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        mockMvc.perform(get("/api/v1/cashflow/forecast").header("Authorization", bearer(a))).andExpect(status().isOk());
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM cash_flow_snapshots WHERE user_id = ?", Long.class, a);

        assertThat(snapshotRepository.findByIdAndUserId(id, a)).isPresent();
        assertThat(snapshotRepository.findByIdAndUserId(id, b)).isEmpty();
    }

    // ---- validation and authentication ----

    @Test
    @DisplayName("An invalid horizon returns 400 ProblemDetail and stores nothing")
    void get_invalidHorizon_400() throws Exception {
        long a = USERS.incrementAndGet();
        for (var h : new String[]{"45", "abc", "030", "-30"}) {
            mockMvc.perform(get("/api/v1/cashflow/forecast").param("horizon", h).header("Authorization", bearer(a)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"));
        }
        assertThat(snapshotRows(a)).isZero();
    }

    @Test
    @DisplayName("No token returns 401 on both cash-flow endpoints")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/cashflow/forecast")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cashflow/refresh")).andExpect(status().isUnauthorized());
    }
}
