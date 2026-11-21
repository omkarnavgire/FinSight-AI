package com.finsight.accounts.repository;

import com.finsight.accounts.domain.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Account}. Every query is scoped by {@code userId} (AC-OWN-2).
 *
 * <p>Extends the minimal {@link Repository} instead of {@code JpaRepository} on purpose:
 * the unscoped {@code findById}, {@code findAll} and {@code deleteById} methods do not exist,
 * so they cannot be called by mistake. Only accounts-module code may use this repository
 * (enforced by {@code ModuleBoundaryTest}).
 */
public interface AccountRepository extends Repository<Account, Long> {

    /**
     * Persists a new account or the {@code active} flag of an existing one.
     * Balance columns are {@code updatable = false} and are never written by this method.
     */
    Account save(Account account);

    /**
     * Finds an account only if it belongs to the given user.
     * Empty for a missing account and for another user's account alike.
     */
    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /** All of the user's accounts, active and deactivated, oldest first. */
    List<Account> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    /** One page of the user's accounts, oldest first (Q-5). */
    Page<Account> findPageByUserIdOrderByCreatedAtAscIdAsc(Long userId, Pageable pageable);

    /**
     * Atomically adds {@code delta} to the balance of an owned, active account.
     *
     * <p>Owner and active state are part of the {@code WHERE} clause, so a forged account ID
     * or a concurrent deactivation can never be bypassed. {@code opening_balance} is never touched.
     *
     * <p>Returns the number of rows matched (MySQL Connector/J default), so a zero delta on a
     * valid account returns 1. Returns 0 when the account is missing, not owned, or inactive.
     *
     * <p>Clears the persistence context afterwards so no stale {@code Account} stays in memory.
     * Callers must not rely on entities loaded earlier in the same transaction staying managed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET balance = balance + :delta
             WHERE id = :id
               AND user_id = :userId
               AND active = TRUE
            """, nativeQuery = true)
    int applyDelta(@Param("id") Long id, @Param("userId") Long userId, @Param("delta") BigDecimal delta);
}
