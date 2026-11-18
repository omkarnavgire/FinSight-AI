package com.finsight.accounts;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code AccountController} (T-24) on MySQL 8, plus the J-7 checks.
 *
 * <p>Access tokens are signed with the test secret through {@link JwtService}. The
 * {@code accounts} table has no FK to {@code users}, so no user rows are needed.
 * Ownership effects are verified with raw SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountControllerIT {

    private static final String JWT_SECRET = "accounts-controller-it-secret-at-least-32-chars!";
    private static final long USER_A = 201L;
    private static final long USER_B = 202L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_accounts_ctl_it")
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String bearer(long userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "user" + userId + "@test.com", "USER");
    }

    private Long createAccount(long owner, String body) throws Exception {
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String validBody(String name) {
        return """
                {"name":"%s","type":"CHECKING","openingBalance":1000.00,"currency":"USD"}
                """.formatted(name);
    }

    // ---- create ----

    @Test
    @DisplayName("Create returns 201 with Location, balance equal to opening balance, and no userId")
    void create_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("Main")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/accounts/\\d+")))
                .andExpect(jsonPath("$.balance").value(1000.00))
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    @DisplayName("Create allows a negative opening balance and defaults currency to USD")
    void create_negativeOpening_defaultCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Card","type":"CREDIT","openingBalance":-250.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(-250.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    @DisplayName("Create with invalid fields returns 400 ProblemDetail")
    void create_invalid_returns400() throws Exception {
        for (var body : new String[]{
                """
                {"name":"","type":"CHECKING","openingBalance":1}""",
                """
                {"name":"X","type":"CHECKING","openingBalance":1,"currency":"usd"}""",
                """
                {"name":"X","type":"CHECKING","openingBalance":1.234}""",
                """
                {"name":"X","openingBalance":1}"""}) {
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", bearer(USER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Test
    @DisplayName("A userId in the request body is ignored; the owner comes from the token")
    void create_clientUserIdIgnored() throws Exception {
        var id = createAccount(USER_A, """
                {"name":"Forged","type":"CHECKING","openingBalance":1,"userId":%d}
                """.formatted(USER_B));

        var owner = jdbc.queryForObject("SELECT user_id FROM accounts WHERE id = ?", Long.class, id);
        assertThat(owner).isEqualTo(USER_A);
    }

    // ---- ownership (AC-OWN-1) ----

    @Test
    @DisplayName("User B reading user A's account gets 404")
    void get_otherUsersAccount_returns404() throws Exception {
        var id = createAccount(USER_A, validBody("A-private"));

        mockMvc.perform(get("/api/v1/accounts/{id}", id).header("Authorization", bearer(USER_B)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("User B deactivating user A's account gets 404 and A's account stays active")
    void delete_otherUsersAccount_returns404_andUnchanged() throws Exception {
        var id = createAccount(USER_A, validBody("A-keep"));

        mockMvc.perform(delete("/api/v1/accounts/{id}", id).header("Authorization", bearer(USER_B)))
                .andExpect(status().isNotFound());

        var active = jdbc.queryForObject("SELECT active FROM accounts WHERE id = ?", Boolean.class, id);
        assertThat(active).isTrue();
    }

    @Test
    @DisplayName("User B's list does not contain user A's accounts")
    void list_excludesOtherUsersAccounts() throws Exception {
        var id = createAccount(USER_A, validBody("A-hidden"));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", bearer(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(id.intValue()))));
    }

    @Test
    @DisplayName("Reading a non-existent account returns 404")
    void get_missingAccount_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", 999_999).header("Authorization", bearer(USER_A)))
                .andExpect(status().isNotFound());
    }

    // ---- deactivation ----

    @Test
    @DisplayName("Deactivate twice returns 204 both times; balances unchanged; still listed as inactive")
    void deactivate_idempotent_listedAsInactive() throws Exception {
        var id = createAccount(USER_A, validBody("ToClose"));

        mockMvc.perform(delete("/api/v1/accounts/{id}", id).header("Authorization", bearer(USER_A)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/accounts/{id}", id).header("Authorization", bearer(USER_A)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/accounts/{id}", id).header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.balance").value(1000.00))
                .andExpect(jsonPath("$.openingBalance").value(1000.00));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", bearer(USER_A)))
                .andExpect(jsonPath("$[?(@.id == %d)].active".formatted(id), contains(false)));
    }

    // ---- J-7: unauthenticated requests should be 401 ----

    @Test
    @DisplayName("J-7: no Authorization header returns 401")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("J-7: malformed bearer token returns 401")
    void malformedToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("J-7: expired bearer token returns 401")
    void expiredToken_returns401() throws Exception {
        var expired = new JwtService(JWT_SECRET, -60_000L).issueAccessToken(USER_A, "a@test.com", "USER");

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }
}
