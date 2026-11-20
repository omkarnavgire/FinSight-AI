package com.finsight.auth.repository;

import com.finsight.auth.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * Keyset page of user IDs greater than {@code lastId}, in ascending order (Phase 8).
     * Only IDs are returned; no other user data leaves the auth module.
     *
     * @param lastId   exclusive lower bound; {@code 0} for the first page
     * @param pageable page size only; always use page index 0 (keyset, not offset)
     * @return up to {@code pageable.getPageSize()} IDs
     */
    @Query("SELECT u.id FROM User u WHERE u.id > :lastId ORDER BY u.id ASC")
    List<Long> findIdsAfter(@Param("lastId") long lastId, Pageable pageable);
}
