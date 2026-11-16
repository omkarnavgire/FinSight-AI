package com.finsight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Spring Boot Actuator health endpoint responds with HTTP 200
 * and reports status "UP".
 *
 * <p>Uses an in-memory H2 datasource. Flyway is disabled so the health check
 * exercises only the application layer, not database migrations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "finsight.security.jwt.secret=test-secret-must-be-at-least-32-chars-long",
        "finsight.security.cookie.secure=false",
        "management.endpoint.health.show-details=always"
})
class HealthEndpointIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /actuator/health returns HTTP 200 and status UP")
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
