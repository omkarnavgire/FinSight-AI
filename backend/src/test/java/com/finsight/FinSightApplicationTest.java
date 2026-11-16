package com.finsight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that the Spring application context loads successfully.
 *
 * <p>Uses an in-memory H2 datasource so no external MySQL is required.
 * Flyway is disabled for this context-load test; migration correctness
 * is verified separately in {@link com.finsight.FlywayMigrationIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "finsight.security.jwt.secret=test-secret-must-be-at-least-32-chars-long",
        "finsight.security.cookie.secure=false"
})
class FinSightApplicationTest {

    @Test
    @DisplayName("Spring application context loads without errors")
    void contextLoads() {
        // If the context fails to load, Spring throws before this line.
    }
}
