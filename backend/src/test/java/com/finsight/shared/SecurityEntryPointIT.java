package com.finsight.shared;

import com.finsight.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * J-7: unauthenticated vs. unauthorised responses.
 *
 * <ul>
 *   <li>No, malformed or expired token on a protected endpoint -> 401 with a ProblemDetail body.</li>
 *   <li>Valid token but missing role -> 403 (Spring Security default, unchanged).</li>
 * </ul>
 *
 * <p>No current endpoint requires a role, so a probe endpoint guarded by {@code hasRole('ADMIN')}
 * is registered for this test only. It is a nested class of this test, so Spring Boot's test
 * type-exclude filter keeps it out of every other application context.
 *
 * <p>Uses the in-memory {@code test} profile (no database is needed to check HTTP status codes).
 * The same 401 cases also run against MySQL 8 in {@code AccountControllerIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityEntryPointIT.AdminProbeController.class)
class SecurityEntryPointIT {

    private static final String PROBE = "/api/v1/test-only/admin-probe";

    @RestController
    static class AdminProbeController {
        @GetMapping(PROBE)
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Value("${finsight.security.jwt.secret}")
    private String jwtSecret;

    private String token(String role) {
        return "Bearer " + jwtService.issueAccessToken(301L, "probe@test.com", role);
    }

    @Test
    @DisplayName("No token: 401, WWW-Authenticate: Bearer, ProblemDetail body")
    void noToken_401WithProblemDetail() throws Exception {
        mockMvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    @Test
    @DisplayName("Malformed bearer token: 401")
    void malformedToken_401() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Expired JWT: 401 with the same body (failure reason is not revealed)")
    void expiredToken_401() throws Exception {
        var expired = new JwtService(jwtSecret, -60_000L).issueAccessToken(301L, "probe@test.com", "ADMIN");

        mockMvc.perform(get(PROBE).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    @Test
    @DisplayName("Authenticated USER on an ADMIN-only endpoint: 403 (not 401)")
    void authenticatedWithoutRole_403() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", token("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Authenticated ADMIN on the ADMIN-only endpoint: 200")
    void authenticatedWithRole_200() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", token("ADMIN")))
                .andExpect(status().isOk());
    }
}
