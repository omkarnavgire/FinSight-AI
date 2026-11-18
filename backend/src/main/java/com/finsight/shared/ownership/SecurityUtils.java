package com.finsight.shared.ownership;

import com.finsight.shared.exception.AuthenticationFailedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility for extracting the authenticated user identity from the Spring Security context.
 *
 * <h2>Ownership pattern (mandatory for all user-scoped service methods)</h2>
 *
 * <p>Every service method that reads or writes user-scoped data MUST obtain
 * the caller's identity from this class, never from a request body, path variable,
 * or any other client-supplied source. Violating this rule creates an Insecure
 * Direct Object Reference (IDOR) vulnerability.
 *
 * <p>Canonical usage pattern — service layer:
 * <pre>{@code
 * public AccountDto getAccount(Long accountId) {
 *     Long userId = SecurityUtils.currentUserId();   // always from context
 *     return accountRepository.findByIdAndUserId(accountId, userId)
 *             .map(accountMapper::toDto)
 *             .orElseThrow(() -> new AccountNotFoundException(accountId));
 * }
 * }</pre>
 *
 * <p>The principal is a {@code Long} user ID, set by
 * {@link com.finsight.auth.filter.JwtAuthFilter} after successful JWT validation.
 * It is never a username string.
 *
 * <h2>Controller rule</h2>
 * <p>Controllers must annotate every method with {@code @PreAuthorize("isAuthenticated()")}.
 * They must never pass a user ID from the URL or request body to a service method;
 * the service must call {@code SecurityUtils.currentUserId()} itself.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * Returns the authenticated user's ID.
     *
     * <p>The ID was embedded in the JWT as the {@code sub} claim and stored
     * as the principal by {@link com.finsight.auth.filter.JwtAuthFilter}.
     *
     * @return the authenticated user's surrogate PK (never {@code null})
     * @throws AuthenticationFailedException if the security context holds no
     *         authenticated principal — should never happen on a protected
     *         endpoint that has {@code @PreAuthorize("isAuthenticated()")}
     */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new AuthenticationFailedException();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) {
            return id;
        }
        // Defensive: should never happen with JwtAuthFilter wiring.
        throw new AuthenticationFailedException();
    }
}
