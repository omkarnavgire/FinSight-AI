package com.finsight.budgeting;

import com.finsight.auth.service.JwtService;
import com.finsight.budgeting.service.BudgetAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP tests for {@code BudgetController} on MySQL 8 (T-35, T-35c): ownership (404), period validation
 * (400 ProblemDetail), duplicates (409), historical progress, authentication (401) and response shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BudgetControllerIT {

    private static final String JWT_SECRET = "budget-controller-it-secret-must-be-at-least-32-chars!";
    private static final AtomicLong USERS = new AtomicLong(1000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_budget_ctl_it")
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
    @MockitoSpyBean private BudgetAlertService alertService;

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

    private long budget(long user, String body) throws Exception {
        var r = mockMvc.perform(post("/api/v1/budgets").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return idOf(r.getResponse().getContentAsString());
    }

    private static String marchDining(String limit) {
        return "{\"category\":\"DINING\",\"monthlyLimit\":%s,\"periodYear\":2026,\"periodMonth\":3}".formatted(limit);
    }

    private void spend(long user, long acc, String amount, String date) throws Exception {
        mockMvc.perform(post("/api/v1/transactions").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":%s,\"transactionDate\":\"%s\",\"category\":\"DINING\",\"countryCode\":\"US\"}"
                                .formatted(acc, amount, date)))
                .andExpect(status().isCreated());
    }

    // ---- create / list ----

    @Test
    @DisplayName("Create returns 201 without userId; the owner comes from the token, not the body")
    void create_201_ownerFromToken() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();

        var r = mockMvc.perform(post("/api/v1/budgets").header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DINING\",\"monthlyLimit\":400,\"periodYear\":2026,\"periodMonth\":3,\"userId\":%d}"
                                .formatted(b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("DINING"))
                .andExpect(jsonPath("$.periodYear").value(2026))
                .andExpect(jsonPath("$.periodMonth").value(3))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn();

        long id = idOf(r.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT user_id FROM budgets WHERE id = ?", Long.class, id)).isEqualTo(a);
    }

    @Test
    @DisplayName("Create without a period uses the current one; list shows it and only the caller's budgets")
    void create_defaultPeriod_listed() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        var now = YearMonth.now();
        long mine = budget(a, "{\"category\":\"TRANSPORT\",\"monthlyLimit\":100}");
        budget(b, "{\"category\":\"TRANSPORT\",\"monthlyLimit\":100}");

        mockMvc.perform(get("/api/v1/budgets").header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value((int) mine))
                .andExpect(jsonPath("$.content[0].periodYear").value(now.getYear()))
                .andExpect(jsonPath("$.content[0].periodMonth").value(now.getMonthValue()))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Duplicate category and period returns 409 ProblemDetail")
    void create_duplicate_409() throws Exception {
        long a = USERS.incrementAndGet();
        budget(a, marchDining("100"));

        mockMvc.perform(post("/api/v1/budgets").header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON).content(marchDining("200")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("Create validation errors return 400 ProblemDetail")
    void create_invalid_400() throws Exception {
        long a = USERS.incrementAndGet();
        for (var body : new String[]{
                "{\"category\":\"DINING\",\"monthlyLimit\":0}",
                "{\"category\":\"DINING\",\"monthlyLimit\":-5}",
                "{\"category\":\"DINING\",\"monthlyLimit\":1.234}",
                "{\"monthlyLimit\":10}",
                "{\"category\":\"DINING\",\"monthlyLimit\":10,\"periodYear\":2026}",
                "{\"category\":\"DINING\",\"monthlyLimit\":10,\"periodYear\":2026,\"periodMonth\":13}"}) {
            mockMvc.perform(post("/api/v1/budgets").header("Authorization", bearer(a))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE user_id = ?", Integer.class, a)).isZero();
    }

    // ---- ownership (T-35c) ----

    @Test
    @DisplayName("User B gets 404 on progress, PUT and DELETE of A's budget; A's budget is unchanged")
    void otherUser_404_onEveryEndpoint() throws Exception {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long id = budget(a, marchDining("100"));

        mockMvc.perform(get("/api/v1/budgets/{id}/progress", id).header("Authorization", bearer(b)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(put("/api/v1/budgets/{id}", id).header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimit\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/budgets/{id}", id).header("Authorization", bearer(b)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", bearer(b)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));

        var limit = jdbc.queryForObject("SELECT monthly_limit FROM budgets WHERE id = ?", BigDecimal.class, id);
        assertThat(limit).isEqualByComparingTo("100.00");
    }

    // ---- progress ----

    @Test
    @DisplayName("Progress for the own period reports spending and fired alerts; ?year=2025&month=3 reports that month")
    void progress_ownAndHistorical() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        long id = budget(a, marchDining("100"));
        spend(a, acc, "-85.00", "2026-03-10");
        spend(a, acc, "-25.00", "2025-03-04");

        mockMvc.perform(get("/api/v1/budgets/{id}/progress", id).param("year", "2026").param("month", "3")
                        .header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spent").value(85.00))
                .andExpect(jsonPath("$.remaining").value(15.00))
                .andExpect(jsonPath("$.percentUsed").value(85.00))
                .andExpect(jsonPath("$.alertsFired", contains(80)))
                .andExpect(jsonPath("$.userId").doesNotExist());

        mockMvc.perform(get("/api/v1/budgets/{id}/progress", id).param("year", "2025").param("month", "3")
                        .header("Authorization", bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2025))
                .andExpect(jsonPath("$.month").value(3))
                .andExpect(jsonPath("$.spent").value(25.00))
                .andExpect(jsonPath("$.alertsFired", empty()));
    }

    @Test
    @DisplayName("Progress period must be both or neither and valid: otherwise 400 ProblemDetail")
    void progress_invalidPeriod_400() throws Exception {
        long a = USERS.incrementAndGet();
        long id = budget(a, marchDining("100"));
        String[][] cases = {{"2026", null}, {null, "3"}, {"abc", "3"}, {"2026", "x"}, {"2026", "13"}, {"2026", "0"}, {"1999", "3"}};
        for (var c : cases) {
            var req = get("/api/v1/budgets/{id}/progress", id).header("Authorization", bearer(a));
            if (c[0] != null) req = req.param("year", c[0]);
            if (c[1] != null) req = req.param("month", c[1]);
            mockMvc.perform(req)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"));
        }
    }

    // ---- update / delete ----

    @Test
    @DisplayName("PUT changes only the limit; DELETE returns 204 and removes the budget and its alerts")
    void update_thenDelete() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        long id = budget(a, marchDining("100"));
        spend(a, acc, "-90.00", "2026-03-10");

        mockMvc.perform(put("/api/v1/budgets/{id}", id).header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyLimit\":80,\"category\":\"OTHER\",\"periodMonth\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyLimit").value(80))
                .andExpect(jsonPath("$.category").value("DINING"))
                .andExpect(jsonPath("$.periodMonth").value(3));
        assertThat(jdbc.queryForList("SELECT threshold_pct FROM budget_alerts WHERE budget_id = ? ORDER BY threshold_pct",
                Integer.class, id)).containsExactly(80, 100);

        mockMvc.perform(delete("/api/v1/budgets/{id}", id).header("Authorization", bearer(a)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_alerts WHERE budget_id = ?", Integer.class, id)).isZero();
    }

    // ---- failure isolation ----

    @Test
    @DisplayName("A failing alert evaluation never breaks POST /api/v1/transactions (201, data committed)")
    void alertFailure_doesNotBreakTransactionRequest() throws Exception {
        long a = USERS.incrementAndGet();
        long acc = account(a);
        budget(a, marchDining("100"));
        doThrow(new IllegalStateException("simulated")).when((BudgetAlertService) AopTestUtils.getUltimateTargetObject(alertService))
                .evaluateAffectedInNewTransaction(any());

        spend(a, acc, "-90.00", "2026-03-10");

        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, acc))
                .isEqualByComparingTo("910.00");
    }

    // ---- authentication ----

    @Test
    @DisplayName("No token returns 401 on every budget endpoint")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/budgets")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/budgets/1/progress")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/budgets/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/budgets/1")).andExpect(status().isUnauthorized());
    }
}
