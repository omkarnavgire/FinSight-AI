package com.finsight.auth;

import com.finsight.auth.domain.Role;
import com.finsight.auth.domain.User;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.AuthService;
import com.finsight.auth.service.JwtService;
import com.finsight.auth.service.TokenService;
import com.finsight.shared.exception.AuthenticationFailedException;
import com.finsight.shared.exception.InvalidTokenException;
import com.finsight.shared.exception.RateLimitExceededException;
import com.finsight.shared.security.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService}: login, register, refresh and logout logic. Token state rules
 * live in {@code TokenServiceTest}/{@code TokenServiceIT}; HTTP and cookies in {@code AuthControllerIT}.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "demo@finsight.test";
    private static final String IP = "203.0.113.9";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TokenService tokenService;
    @Mock private RateLimiter rateLimiter;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, tokenService, rateLimiter);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A saved user (the id is normally set by the database). */
    private static User storedUser(Role role) {
        var user = User.create(EMAIL, "stored-hash", "Demo User");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "role", role);
        return user;
    }

    @Nested
    @DisplayName("login")
    class Login {

        private final LoginRequest request = new LoginRequest(EMAIL, "Secret#123");

        @Test
        @DisplayName("Correct credentials issue an access token and a refresh token for the user")
        void success_issuesTokens() {
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(true);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(storedUser(Role.USER)));
            when(passwordEncoder.matches("Secret#123", "stored-hash")).thenReturn(true);
            when(jwtService.issueAccessToken(USER_ID, EMAIL, "USER")).thenReturn("access");
            when(tokenService.issue(USER_ID)).thenReturn("raw-refresh");

            var result = authService.login(request, IP);

            assertThat(result).isEqualTo(new AuthService.AuthResult("access", "raw-refresh", USER_ID));
        }

        @Test
        @DisplayName("Attempts are limited to 5 per 15 minutes for the client IP (AC-AUTH-5)")
        void rateLimit_usesFiveAttemptsPerFifteenMinutesPerIp() {
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(true);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request, IP)).isInstanceOf(AuthenticationFailedException.class);

            verify(rateLimiter).tryConsume(IP, 5, 900L);
        }

        @Test
        @DisplayName("Over the limit is refused with Retry-After seconds, before any user lookup")
        void overLimit_refusedBeforeLookup() {
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(false);
            when(rateLimiter.retryAfterSeconds(IP, 900L)).thenReturn(840L);

            assertThatThrownBy(() -> authService.login(request, IP))
                    .isInstanceOfSatisfying(RateLimitExceededException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(840L));
            verifyNoInteractions(userRepository, passwordEncoder, jwtService, tokenService);
        }

        @Test
        @DisplayName("An unknown email fails with the generic error and issues no tokens")
        void unknownEmail_genericFailure() {
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(true);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request, IP))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Invalid credentials");
            verifyNoInteractions(jwtService, tokenService);
        }

        @Test
        @DisplayName("A wrong password fails with the same generic error and issues no tokens")
        void wrongPassword_sameGenericFailure() {
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(true);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(storedUser(Role.USER)));
            when(passwordEncoder.matches("Secret#123", "stored-hash")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request, IP))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Invalid credentials");
            verifyNoInteractions(jwtService, tokenService);
        }

        @Test
        @DisplayName("The email is looked up exactly as sent: no trimming or case change (finding F-1)")
        void email_passedThroughUnchanged() {
            var raw = " Demo@FinSight.Test ";
            when(rateLimiter.tryConsume(IP, 5, 900L)).thenReturn(true);
            when(userRepository.findByEmail(raw)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest(raw, "x"), IP))
                    .isInstanceOf(AuthenticationFailedException.class);

            verify(userRepository).findByEmail(raw);
        }
    }

    @Nested
    @DisplayName("register")
    class Register {

        private final RegisterRequest request = new RegisterRequest(EMAIL, "Secret#123", "Demo User");

        @Test
        @DisplayName("A new email is saved with a hashed password, never the raw one, and gets tokens")
        void success_savesHashedUserAndIssuesTokens() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode("Secret#123")).thenReturn("bcrypt-hash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", USER_ID);
                return saved;
            });
            when(jwtService.issueAccessToken(USER_ID, EMAIL, "USER")).thenReturn("access");
            when(tokenService.issue(USER_ID)).thenReturn("raw-refresh");

            var result = authService.register(request);

            var captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            var saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getFullName()).isEqualTo("Demo User");
            assertThat(saved.getPassword()).isEqualTo("bcrypt-hash").isNotEqualTo("Secret#123");
            assertThat(saved.getRole()).isEqualTo(Role.USER);
            assertThat(result).isEqualTo(new AuthService.AuthResult("access", "raw-refresh", USER_ID));
        }

        @Test
        @DisplayName("A duplicate email is 409 and nothing is hashed, saved or issued")
        void duplicateEmail_conflictAndNothingSaved() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getReason()).isEqualTo("An account with this email already exists");
                    });
            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder, jwtService, tokenService);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("A valid refresh token rotates and issues an access token with the stored email and role")
        void success_rotatesAndIssuesAccessToken() {
            when(tokenService.rotate("old")).thenReturn(new TokenService.RotationResult("new", USER_ID));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(storedUser(Role.USER)));
            when(jwtService.issueAccessToken(USER_ID, EMAIL, "USER")).thenReturn("access");

            var result = authService.refresh("old");

            assertThat(result).isEqualTo(new AuthService.AuthResult("access", "new", USER_ID));
        }

        @Test
        @DisplayName("The access token carries the user's stored role (ADMIN)")
        void role_comesFromStoredUser() {
            when(tokenService.rotate("old")).thenReturn(new TokenService.RotationResult("new", USER_ID));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(storedUser(Role.ADMIN)));
            when(jwtService.issueAccessToken(USER_ID, EMAIL, "ADMIN")).thenReturn("admin-access");

            assertThat(authService.refresh("old").accessToken()).isEqualTo("admin-access");
        }

        @Test
        @DisplayName("An invalid or revoked refresh token is rejected and no access token is made")
        void invalidToken_rejectedWithoutAccessToken() {
            when(tokenService.rotate("revoked")).thenThrow(new InvalidTokenException("Refresh token has been revoked"));

            assertThatThrownBy(() -> authService.refresh("revoked")).isInstanceOf(InvalidTokenException.class);
            verifyNoInteractions(jwtService, userRepository);
        }

        @Test
        @DisplayName("A user missing after rotation fails safely with the generic error")
        void missingUser_genericFailure() {
            when(tokenService.rotate("old")).thenReturn(new TokenService.RotationResult("new", USER_ID));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("old")).isInstanceOf(AuthenticationFailedException.class);
            verifyNoInteractions(jwtService);
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("Revokes the presented token for the signed-in user only")
        void revokesForCurrentUser() {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(42L, null, List.of()));

            authService.logout("raw-refresh");

            verify(tokenService).revokeForLogout("raw-refresh", 42L);
        }

        @ParameterizedTest(name = "cookie value: \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("Without a refresh token there is nothing to revoke")
        void noToken_noRevocation(String raw) {
            authService.logout(raw);

            verifyNoInteractions(tokenService);
        }

        @Test
        @DisplayName("Without a signed-in user the token is not revoked")
        void noSignedInUser_refused() {
            assertThatThrownBy(() -> authService.logout("raw-refresh")).isInstanceOf(AuthenticationFailedException.class);
            verify(tokenService, never()).revokeForLogout(anyString(), anyLong());
        }
    }
}
