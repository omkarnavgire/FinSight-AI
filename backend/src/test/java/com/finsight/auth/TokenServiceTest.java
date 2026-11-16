package com.finsight.auth;

import com.finsight.auth.domain.RefreshToken;
import com.finsight.auth.domain.RevocationReason;
import com.finsight.auth.repository.RefreshTokenRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.shared.exception.InvalidTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenService} — covers all revocation-reason paths.
 *
 * <p>Tests (T-15):
 * <ul>
 *   <li>Rotation sets {@code revocation_reason='ROTATED'} on the old token.</li>
 *   <li>Re-use of a {@code ROTATED} token triggers bulk revocation → {@code REUSE_DETECTED}.</li>
 *   <li>Re-use of a {@code REUSE_DETECTED} token returns 401 without further bulk revocation.</li>
 *   <li>Re-use of a {@code LOGOUT} token returns {@link InvalidTokenException} without
 *       calling {@code revokeAllActiveForUser}.</li>
 *   <li>Expired token returns {@link InvalidTokenException} regardless of revocation state.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private RefreshTokenRepository tokenRepository;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(tokenRepository, 604_800_000L);
    }

    @Test
    @DisplayName("Successful rotation marks old token as ROTATED and issues a new token")
    void rotate_setsRevocationReasonRotated() {
        var rawToken = "test-raw-token";
        var hash = TokenService.sha256Hex(rawToken);
        var stored = RefreshToken.create(1L, hash, LocalDateTime.now().plusDays(7));

        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(stored));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tokenService.rotate(rawToken);

        assertThat(stored.isRevoked()).isTrue();
        assertThat(stored.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
        verify(tokenRepository, never()).revokeAllActiveForUser(any(), any());
    }

    @Test
    @DisplayName("Re-use of ROTATED token triggers bulk revocation with REUSE_DETECTED and returns 401")
    void rotate_rotatedTokenReuse_triggersReuseDetection() {
        var rawToken = "rotated-raw-token";
        var hash = TokenService.sha256Hex(rawToken);
        var stored = RefreshToken.create(42L, hash, LocalDateTime.now().plusDays(7));
        stored.revoke(RevocationReason.ROTATED);

        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> tokenService.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);

        verify(tokenRepository).revokeAllActiveForUser(42L, RevocationReason.REUSE_DETECTED);
    }

    @Test
    @DisplayName("Re-use of REUSE_DETECTED token returns 401 without triggering another bulk revocation")
    void rotate_reuseDetectedToken_returns401WithoutCascade() {
        var rawToken = "reuse-detected-token";
        var hash = TokenService.sha256Hex(rawToken);
        var stored = RefreshToken.create(42L, hash, LocalDateTime.now().plusDays(7));
        stored.revoke(RevocationReason.REUSE_DETECTED);

        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> tokenService.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);

        // No bulk revocation for REUSE_DETECTED re-use (AC-TOK-2b).
        verify(tokenRepository, never()).revokeAllActiveForUser(any(), any());
    }

    @Test
    @DisplayName("Re-use of LOGOUT-revoked token returns 401 without calling revokeAllActiveForUser")
    void rotate_logoutToken_returns401WithoutRevokeAll() {
        var rawToken = "logout-token";
        var hash = TokenService.sha256Hex(rawToken);
        var stored = RefreshToken.create(99L, hash, LocalDateTime.now().plusDays(7));
        stored.revoke(RevocationReason.LOGOUT);

        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> tokenService.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);

        verify(tokenRepository, never()).revokeAllActiveForUser(any(), any());
    }

    @Test
    @DisplayName("Expired token returns InvalidTokenException regardless of revoked status")
    void rotate_expiredToken_throwsInvalidTokenException() {
        var rawToken = "expired-token";
        var hash = TokenService.sha256Hex(rawToken);
        // expiresAt in the past
        var stored = RefreshToken.create(1L, hash, LocalDateTime.now().minusSeconds(1));

        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> tokenService.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");

        verify(tokenRepository, never()).revokeAllActiveForUser(any(), any());
    }
}
