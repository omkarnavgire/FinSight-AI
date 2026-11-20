package com.finsight.auth;

import com.finsight.auth.service.UserDirectory;
import com.finsight.shared.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link UserDirectory} on MySQL 8 (Phase 8, D-1): keyset order, limit and bounds. */
@SpringBootTest
@Testcontainers
class UserDirectoryIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_user_dir_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "user-directory-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private UserDirectory userDirectory;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedUsers() {
        for (long id = 5001; id <= 5005; id++) {
            jdbc.update("""
                    INSERT IGNORE INTO users (id, email, password_hash, full_name, role, created_at, updated_at)
                    VALUES (?, ?, 'hash', 'IT User', 'USER', NOW(), NOW())
                    """, id, "dir-" + id + "@test.com");
        }
    }

    @Test
    @DisplayName("Pages are in ascending ID order, respect the limit, and exclude lastId")
    void keysetPaging() {
        assertThat(userDirectory.userIdsAfter(0, 3)).containsExactly(5001L, 5002L, 5003L);
        assertThat(userDirectory.userIdsAfter(5003, 3)).containsExactly(5004L, 5005L);
        assertThat(userDirectory.userIdsAfter(5005, 3)).isEmpty();
    }

    @Test
    @DisplayName("A negative lastId or a limit outside 1-1000 is rejected")
    void invalidArguments() {
        assertThatThrownBy(() -> userDirectory.userIdsAfter(-1, 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> userDirectory.userIdsAfter(0, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> userDirectory.userIdsAfter(0, 1001)).isInstanceOf(BadRequestException.class);
    }
}
