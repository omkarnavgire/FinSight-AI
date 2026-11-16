package com.finsight.auth.repository;

import com.finsight.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link User} entities.
 *
 * <p>All queries are scoped to auth module use only. Other modules must not
 * import this repository — cross-module user lookups go through the
 * {@code SecurityContext} or a shared service interface.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by email address (case-sensitive, as stored).
     *
     * @param email the email to look up
     * @return the matching user, or empty if not found
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether an email is already registered.
     *
     * @param email the email to check
     * @return {@code true} if a user with this email exists
     */
    boolean existsByEmail(String email);
}
