package com.finsight.analytics;

import com.finsight.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
 * HTTP tests for {@code AnalyticsController} on MySQL 8 (T-37, T-38): response shape, user
 * isolation, parameter validation (400 ProblemDetail) and authentication (401).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsControllerIT {

    private static final String JWT_SECRET = "analytics-controller-it-secret-must-be-at-least-32-chars!";
    private static final AtomicLong USERS = new AtomicLong(8000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_analytics_ctl_it")
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

    /** "Today" for every test in this class, so default periods never depend on the real date. */
    static final Instant FIXED_NOW = Instant.parse("2026-03-15T12:00:00Z");

    /**
     * Replaces the system clock in this class's context only. {@code @Primary} wins even if the
     * production clock bean is also created. Production clock configuration is unchanged.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private String bearer(long userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u" + userId + "@test.com", "USER");
    }

    private long account(long user) throws Exception {
        var r = mockMvc.perform(post("/api/v1/accounts").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Main\",\"type\":\"CHECKING\",\"openingBalance\":1000.00}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private void tx(long user, long acc, String amount, String date, String category) throws Exception {
        mockMvc.perform(post("/api/v1/transactions").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":%s,\"transactionDate\":\"%s\",\"category\":\"%s\",\"countryCode\":\"US\"}"
                                .formatted(acc, amount, date, category)))
                .andExpect(status().isCreated());
    }

    // ---- category breakdown ----

    @Test
    @DisplayName("Category breakdown returns the exact shape, 2-decimal amounts and percents, and no userId")
    void breakdown_shape() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        tx(a, acc, "-75.00", "2026-03-01", "DINING");
        tx(a, acc, "-25.00", "2026-03-31", "GROCERIES");
        tx(a, acc, "500.00", "2026-03-15", "INCOME");

        var r = mockMvc.perform(get("/api/v1/analytics/category-breakdown").param("year", "2026").param("month", "3")
                        .header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-03-31"))
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].*", hasSize(3)))
                .andExpect(jsonPath("$.items[0].category").value("DINING"))
                .andExpect(jsonPath("$.items[0].amount").value(75.00))
                .andExpect(jsonPath("$.items[0].percent").value(75.00))
                .andExpect(jsonPath("$.items[1].category").value("GROCERIES"))
                .andExpect(jsonPath("$.items[1].percent").value(25.00))
                .andExpect(jsonPath("$..userId").doesNotExist())
                .andReturn();

        assertThat(r.getResponse().getContentAsString())
                .contains("\"total\":100.00", "\"amount\":75.00", "\"percent\":25.00");
    }

    @Test
    @DisplayName("User B sees none of user A's spending in any analytics view")
    void isolation_betweenUsers() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long accA = account(a);
        tx(a, accA, "-60.00", "2026-01-05", "DINING");
        tx(a, accA, "-60.00", "2026-03-10", "DINING");
        account(b);

        mockMvc.perform(get("/api/v1/analytics/category-breakdown").param("year", "2026").param("month", "3")
                        .header("Authorization", bearer(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.items", empty()));
        mockMvc.perform(get("/api/v1/analytics/top-categories").param("end", "2026-03-20")
                        .header("Authorization", bearer(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
        mockMvc.perform(get("/api/v1/analytics/monthly-trend").header("Authorization", bearer(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[*].spent", everyItem(is(0.00))));
    }

    @Test
    @DisplayName("Empty breakdown returns total 0.00 and an empty items list")
    void breakdown_empty() throws Exception {
        long a = USERS.incrementAndGet();

        var r = mockMvc.perform(get("/api/v1/analytics/category-breakdown").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andReturn();

        assertThat(r.getResponse().getContentAsString()).contains("\"total\":0.00");
    }

    // ---- monthly trend ----

    @Test
    @DisplayName("Monthly trend on the fixed clock: October 2025 to March 2026, zero-filled, oldest first")
    void trend_sixMonths() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        tx(a, acc, "-10.00", "2026-03-01", "DINING");
        tx(a, acc, "-5.00", "2026-03-31", "OTHER");
        tx(a, acc, "-30.00", "2026-01-01", "TRANSPORT");
        tx(a, acc, "-99.00", "2025-09-30", "TRANSPORT"); // day before the window
        tx(a, acc, "-99.00", "2026-04-01", "TRANSPORT"); // day after the window

        mockMvc.perform(get("/api/v1/analytics/monthly-trend").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.months", hasSize(6)))
                .andExpect(jsonPath("$.months[0].*", hasSize(3)))
                .andExpect(jsonPath("$.months[*].year", contains(2025, 2025, 2025, 2026, 2026, 2026)))
                .andExpect(jsonPath("$.months[*].month", contains(10, 11, 12, 1, 2, 3)))
                .andExpect(jsonPath("$.months[*].spent", contains(0.00, 0.00, 0.00, 30.00, 0.00, 15.00)));
    }

    @Test
    @DisplayName("Defaults follow the fixed clock: breakdown is March 2026, top categories end on 2026-03-15")
    void defaults_useFixedClock() throws Exception {
        long a = USERS.incrementAndGet();

        mockMvc.perform(get("/api/v1/analytics/category-breakdown").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-03-31"));
        mockMvc.perform(get("/api/v1/analytics/top-categories").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-02-14"))
                .andExpect(jsonPath("$.to").value("2026-03-15"));
    }

    // ---- top categories ----

    @Test
    @DisplayName("Top categories: 30-day window ending on end, five items, percent of all window spending")
    void top_fiveOfWindow() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        tx(a, acc, "-60.00", "2026-02-19", "GROCERIES");      // first day of window (end - 29)
        tx(a, acc, "-40.00", "2026-03-20", "DINING");         // end day
        tx(a, acc, "-30.00", "2026-03-01", "TRANSPORT");
        tx(a, acc, "-30.00", "2026-03-02", "UTILITIES");
        tx(a, acc, "-20.00", "2026-03-03", "ENTERTAINMENT");
        tx(a, acc, "-20.00", "2026-03-04", "OTHER");          // sixth: dropped, still in total
        tx(a, acc, "-500.00", "2026-02-18", "OTHER");         // one day before the window
        tx(a, acc, "-500.00", "2026-03-21", "OTHER");         // one day after the window

        mockMvc.perform(get("/api/v1/analytics/top-categories").param("end", "2026-03-20")
                        .header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-02-19"))
                .andExpect(jsonPath("$.to").value("2026-03-20"))
                .andExpect(jsonPath("$.total").value(200.00))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[*].category",
                        contains("GROCERIES", "DINING", "TRANSPORT", "UTILITIES", "ENTERTAINMENT")))
                .andExpect(jsonPath("$.items[*].percent", contains(30.00, 20.00, 15.00, 15.00, 10.00)));
    }

    // ---- validation ----

    @Test
    @DisplayName("Invalid breakdown periods return 400 ProblemDetail")
    void breakdown_invalid_400() throws Exception {
        long a = USERS.incrementAndGet();
        String[][] cases = {{"2026", null}, {null, "3"}, {"abc", "3"}, {"2026", "13"}, {"1999", "3"}, {"2026", "0"}};
        for (var c : cases) {
            var req = get("/api/v1/analytics/category-breakdown").header("Authorization", bearer(a));
            if (c[0] != null) req = req.param("year", c[0]);
            if (c[1] != null) req = req.param("month", c[1]);
            mockMvc.perform(req)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"));
        }
    }

    @Test
    @DisplayName("Invalid top-categories end dates return 400 ProblemDetail without echoing the value")
    void top_invalid_400() throws Exception {
        long a = USERS.incrementAndGet();
        for (var end : new String[]{"2026-02-30", "2026-1-5", "abc", "2101-01-01", "2026-03-20T00:00"}) {
            var r = mockMvc.perform(get("/api/v1/analytics/top-categories").param("end", end)
                            .header("Authorization", bearer(a)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andReturn();
            assertThat(r.getResponse().getContentAsString()).doesNotContain(end);
        }
    }

    // ---- authentication ----

    @Test
    @DisplayName("No token returns 401 on every analytics endpoint")
    void unauthenticated_401() throws Exception {
        for (var path : new String[]{"/api/v1/analytics/category-breakdown", "/api/v1/analytics/monthly-trend",
                "/api/v1/analytics/top-categories"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("An expired token returns 401 on every analytics endpoint")
    void expiredToken_401() throws Exception {
        var expired = new JwtService(JWT_SECRET, -60_000L).issueAccessToken(USERS.incrementAndGet(), "x@test.com", "USER");
        for (var path : new String[]{"/api/v1/analytics/category-breakdown", "/api/v1/analytics/monthly-trend",
                "/api/v1/analytics/top-categories"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + expired))
                    .andExpect(status().isUnauthorized());
        }
    }
}
