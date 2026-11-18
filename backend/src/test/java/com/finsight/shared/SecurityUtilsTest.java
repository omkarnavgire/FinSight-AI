package com.finsight.shared;

import com.finsight.shared.exception.AuthenticationFailedException;
import com.finsight.shared.ownership.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecurityUtils} (T-19).
 *
 * <p>Verifies that the authenticated user ID is correctly extracted from the
 * Spring Security context, and that unauthenticated access throws
 * {@link AuthenticationFailedException}.
 */
class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Returns the Long userId stored as principal by JwtAuthFilter")
    void currentUserId_authenticatedContext_returnsUserId() {
        var auth = new UsernamePasswordAuthenticationToken(42L, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Empty security context throws AuthenticationFailedException")
    void currentUserId_noAuthentication_throws() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(SecurityUtils::currentUserId)
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("Anonymous authentication (not authenticated) throws AuthenticationFailedException")
    void currentUserId_anonymousAuthentication_throws() {
        // Spring Security sets an AnonymousAuthenticationToken for unauthenticated requests;
        // simulate by using a token whose isAuthenticated() returns false.
        var anonAuth = new UsernamePasswordAuthenticationToken(null, null) {
            @Override public boolean isAuthenticated() { return false; }
        };
        SecurityContextHolder.getContext().setAuthentication(anonAuth);

        assertThatThrownBy(SecurityUtils::currentUserId)
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("Principal stored as String (wrong type) throws AuthenticationFailedException")
    void currentUserId_stringPrincipal_throws() {
        // Guard: if someone wires an auth filter that stores a username string instead of Long.
        var auth = new UsernamePasswordAuthenticationToken("not-a-long", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(SecurityUtils::currentUserId)
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
