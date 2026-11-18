package com.finsight.transactions;

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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP tests for {@code TransactionController} on MySQL 8: ownership (404), validation (400),
 * state conflicts (409), paging/filtering, authentication (401) and response shape.
 *
 * <p>Access tokens are signed with the test secret through {@link JwtService}. Accounts are created
 * through the real accounts API. Data effects are verified with raw SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransactionControllerIT {

    private static final String JWT_SECRET = "tx-controller-it-secret-must-be-at-least-32-chars!";
    private static final long USER_A = 601L;
    private static final long USER_B = 602L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_tx_ctl_it")
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

    private long openAccount(long owner) throws Exception {
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Main","type":"CHECKING","openingBalance":1000.00}"""))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String txBody(long accountId, String amount, String date, String extra) {
        return """
                {"accountId":%d,"amount":%s,"transactionDate":"%s","description":"Pizza place","countryCode":"US"%s}
                """.formatted(accountId, amount, date, extra);
    }

    private long createTx(long owner, long accountId, String amount, String date) throws Exception {
        var result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(accountId, amount, date, "")))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    // ---- create ----

    @Test
    @DisplayName("Create returns 201, auto-categorises, updates the balance, and has no userId")
    void create_201_balanceUpdated() throws Exception {
        long acc = openAccount(USER_A);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(acc, "-25.50", "2026-02-01", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(-25.50))
                .andExpect(jsonPath("$.category").value("DINING"))
                .andExpect(jsonPath("$.manual").value(true))
                .andExpect(jsonPath("$.userId").doesNotExist());

        assertThat(balance(acc)).isEqualByComparingTo("974.50");
    }

    @Test
    @DisplayName("Create ignores a userId in the body; the transaction lands on the caller's own account")
    void create_clientUserIdIgnored() throws Exception {
        long acc = openAccount(USER_A);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(acc, "-1.00", "2026-02-01", ",\"userId\":" + USER_B)))
                .andExpect(status().isCreated());

        var owner = jdbc.queryForObject(
                "SELECT a.user_id FROM transactions t JOIN accounts a ON a.id = t.account_id WHERE t.account_id = ?",
                Long.class, acc);
        assertThat(owner).isEqualTo(USER_A);
    }

    @Test
    @DisplayName("Create validation errors return 400 ProblemDetail and write nothing")
    void create_invalid_400() throws Exception {
        long acc = openAccount(USER_A);
        String[] bodies = {
                txBody(acc, "0.00", "2026-02-01", ""),                                     // zero amount
                txBody(acc, "-1.234", "2026-02-01", ""),                                   // 3 decimals
                """
                {"accountId":%d,"amount":-1,"transactionDate":"2026-02-01","countryCode":"us"}""".formatted(acc),
                """
                {"accountId":%d,"amount":-1,"transactionDate":"2026-02-01","countryCode":"ZZ"}""".formatted(acc),
                """
                {"accountId":%d,"amount":-1,"countryCode":"US"}""".formatted(acc),          // missing date
                """
                {"amount":-1,"transactionDate":"2026-02-01","countryCode":"US"}""",          // missing account
                txBody(acc, "-1.00", "2026-02-01", ",\"description\":\"" + "x".repeat(256) + "\"")
        };
        for (var body : bodies) {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(USER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        var rows = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, acc);
        assertThat(rows).isZero();
        assertThat(balance(acc)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Unknown country code returns 400 with a ProblemDetail that does not echo the value")
    void create_unknownCountry_problemDetail() throws Exception {
        long acc = openAccount(USER_A);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"amount":-1,"transactionDate":"2026-02-01","countryCode":"QQ"}"""
                                .formatted(acc)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail", not(containsString("QQ"))));
    }

    // ---- ownership (AC-OWN-1) ----

    @Test
    @DisplayName("User B posting onto user A's account gets 404 and A's balance is unchanged")
    void create_onOtherUsersAccount_404() throws Exception {
        long accA = openAccount(USER_A);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(accA, "-500.00", "2026-02-01", "")))
                .andExpect(status().isNotFound());

        assertThat(balance(accA)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("User B gets 404 on PUT, DELETE and PATCH of user A's transaction; A's data unchanged")
    void otherUser_writeEndpoints_404() throws Exception {
        long accA = openAccount(USER_A);
        long tx = createTx(USER_A, accA, "-10.00", "2026-02-01");

        mockMvc.perform(put("/api/v1/transactions/{id}", tx)
                        .header("Authorization", bearer(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-999,"transactionDate":"2026-02-01","category":"OTHER"}"""))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/transactions/{id}", tx).header("Authorization", bearer(USER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/transactions/{id}/category", tx)
                        .header("Authorization", bearer(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"INCOME\"}"))
                .andExpect(status().isNotFound());

        var row = jdbc.queryForMap("SELECT amount, category FROM transactions WHERE id = ?", tx);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-10.00");
        assertThat(row.get("category")).isEqualTo("DINING");
        assertThat(balance(accA)).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("User B listing with A's accountId gets 404; B's unfiltered list excludes A's rows")
    void otherUser_list_404_andExcluded() throws Exception {
        long accA = openAccount(USER_A);
        long txA = createTx(USER_A, accA, "-3.00", "2026-02-01");
        openAccount(USER_B);

        mockMvc.perform(get("/api/v1/transactions").param("accountId", String.valueOf(accA))
                        .header("Authorization", bearer(USER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) txA))));
    }

    @Test
    @DisplayName("PUT ignores accountId and countryCode in the body (not editable)")
    void update_ignoresNonEditableFields() throws Exception {
        long accA = openAccount(USER_A);
        long accA2 = openAccount(USER_A);
        long tx = createTx(USER_A, accA, "-10.00", "2026-02-01");

        mockMvc.perform(put("/api/v1/transactions/{id}", tx)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-20,"transactionDate":"2026-02-02","category":"OTHER",
                                 "accountId":%d,"countryCode":"FR"}""".formatted(accA2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value((int) accA))
                .andExpect(jsonPath("$.countryCode").value("US"));

        assertThat(balance(accA)).isEqualByComparingTo("980.00");
        assertThat(balance(accA2)).isEqualByComparingTo("1000.00");
    }

    // ---- state conflicts (409) ----

    @Test
    @DisplayName("Deactivated account: create, edit and delete return 409; balance unchanged")
    void deactivatedAccount_409() throws Exception {
        long acc = openAccount(USER_A);
        long tx = createTx(USER_A, acc, "-10.00", "2026-02-01");
        mockMvc.perform(delete("/api/v1/accounts/{id}", acc).header("Authorization", bearer(USER_A)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(acc, "-1.00", "2026-02-01", "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mockMvc.perform(put("/api/v1/transactions/{id}", tx)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-2,"transactionDate":"2026-02-01","category":"OTHER"}"""))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/transactions/{id}", tx).header("Authorization", bearer(USER_A)))
                .andExpect(status().isConflict());

        assertThat(balance(acc)).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("Non-manual (system) transaction: edit and delete return 409; category override is allowed")
    void nonManualTransaction_409_overrideAllowed() throws Exception {
        long acc = openAccount(USER_A);
        jdbc.update("INSERT INTO transactions (account_id, amount, category, description, transaction_date, country_code, manual) "
                + "VALUES (?, -7.00, 'OTHER', 'seeded', '2026-01-01', 'US', FALSE)", acc);
        long seeded = jdbc.queryForObject("SELECT id FROM transactions WHERE account_id = ? AND manual = FALSE", Long.class, acc);

        mockMvc.perform(put("/api/v1/transactions/{id}", seeded)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-8,"transactionDate":"2026-01-01","category":"OTHER"}"""))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/transactions/{id}", seeded).header("Authorization", bearer(USER_A)))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/transactions/{id}/category", seeded)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"GROCERIES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("GROCERIES"));
    }

    // ---- own writes ----

    @Test
    @DisplayName("Own edit, override and delete succeed with the correct balance effects")
    void ownWrites_succeed() throws Exception {
        long acc = openAccount(USER_A);
        long tx = createTx(USER_A, acc, "-50.00", "2026-02-01");

        mockMvc.perform(put("/api/v1/transactions/{id}", tx)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-80,"transactionDate":"2026-02-01","category":"DINING","description":"x"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(-80));
        assertThat(balance(acc)).isEqualByComparingTo("920.00");

        mockMvc.perform(patch("/api/v1/transactions/{id}/category", tx)
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ENTERTAINMENT\"}"))
                .andExpect(status().isOk());
        assertThat(balance(acc)).isEqualByComparingTo("920.00");

        mockMvc.perform(delete("/api/v1/transactions/{id}", tx).header("Authorization", bearer(USER_A)))
                .andExpect(status().isNoContent());
        assertThat(balance(acc)).isEqualByComparingTo("1000.00");
    }

    // ---- list: paging, order, filters ----

    @Test
    @DisplayName("List returns PageResponse, newest first, default size 20, size capped at 100")
    void list_paging() throws Exception {
        long acc = openAccount(USER_A);
        long older = createTx(USER_A, acc, "-1.00", "2026-01-01");
        long newer = createTx(USER_A, acc, "-2.00", "2026-03-01");

        mockMvc.perform(get("/api/v1/transactions").param("accountId", String.valueOf(acc))
                        .header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value((int) newer))
                .andExpect(jsonPath("$.content[1].id").value((int) older))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist());

        mockMvc.perform(get("/api/v1/transactions").param("size", "500").header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("Filters: date range, category, amount range; invalid ranges and paging return 400")
    void list_filters() throws Exception {
        long acc = openAccount(USER_A);
        long jan = createTx(USER_A, acc, "-10.00", "2026-01-10");
        long feb = createTx(USER_A, acc, "-200.00", "2026-02-10");
        String a = String.valueOf(acc);

        mockMvc.perform(get("/api/v1/transactions").param("accountId", a)
                        .param("from", "2026-02-01").param("to", "2026-02-28")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(jsonPath("$.content[*].id", contains((int) feb)));
        mockMvc.perform(get("/api/v1/transactions").param("accountId", a)
                        .param("minAmount", "-50").param("maxAmount", "0")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(jsonPath("$.content[*].id", contains((int) jan)));
        mockMvc.perform(get("/api/v1/transactions").param("accountId", a).param("category", "DINING")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/transactions").param("accountId", a).param("category", "INCOME")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/transactions").param("from", "2026-03-01").param("to", "2026-01-01")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("minAmount", "10").param("maxAmount", "1")
                        .header("Authorization", bearer(USER_A)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("page", "-1").header("Authorization", bearer(USER_A)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("size", "0").header("Authorization", bearer(USER_A)))
                .andExpect(status().isBadRequest());
    }

    // ---- authentication (J-7 regression) ----

    @Test
    @DisplayName("No token, malformed token and expired token return 401 on transaction endpoints")
    void unauthenticated_401() throws Exception {
        var expired = new JwtService(JWT_SECRET, -60_000L).issueAccessToken(USER_A, "a@test.com", "USER");

        mockMvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer junk"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }
}
