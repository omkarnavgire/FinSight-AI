package com.finsight.auth;

import com.finsight.auth.service.JwtService;
import com.finsight.shared.exception.InvalidTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}: issuing and validating HS256 access tokens. HTTP-level 401
 * handling is covered by {@code SecurityEntryPointIT}.
 *
 * <p>Open finding F-4 (not tested on purpose): a correctly signed token whose {@code sub} is not a
 * number makes {@code extractUserId} throw {@code NumberFormatException}, not
 * {@code InvalidTokenException}. The method is unused in production.
 */
class JwtServiceTest {

    /** Test-only secret (at least 32 bytes for HS256). */
    private static final String SECRET = "jwt-service-test-secret-at-least-32-bytes!";
    private static final long FIFTEEN_MINUTES_MS = 900_000L;

    private final JwtService jwtService = new JwtService(SECRET, FIFTEEN_MINUTES_MS);

    @Test
    @DisplayName("An issued token carries the user id, email and role")
    void issue_claimsRoundTrip() {
        var claims = jwtService.validateAndParse(jwtService.issueAccessToken(7L, "demo@finsight.test", "USER"));

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("email", String.class)).isEqualTo("demo@finsight.test");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    @DisplayName("An issued token expires 15 minutes after it was issued")
    void issue_expiresAfterFifteenMinutes() {
        var claims = jwtService.validateAndParse(jwtService.issueAccessToken(7L, "a@b.test", "USER"));

        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(FIFTEEN_MINUTES_MS);
    }

    @Test
    @DisplayName("An issued token is signed with HS256")
    void issue_signedWithHs256() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        var token = jwtService.issueAccessToken(7L, "a@b.test", "USER");

        var header = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getHeader();

        assertThat(header.getAlgorithm()).isEqualTo("HS256");
    }

    @ParameterizedTest(name = "token: \"{0}\"")
    @ValueSource(strings = {"x.y.z", "abc", "", "a.b"})
    @DisplayName("Malformed tokens are refused without echoing the token")
    void malformed_refused(String token) {
        assertThatThrownBy(() -> jwtService.validateAndParse(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("JWT validation failed");
    }

    @Test
    @DisplayName("A null token is refused")
    void null_refused() {
        assertThatThrownBy(() -> jwtService.validateAndParse(null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("An expired token is refused")
    void expired_refused() {
        var alreadyExpired = new JwtService(SECRET, -1_000L);
        var token = alreadyExpired.issueAccessToken(7L, "a@b.test", "USER");

        assertThatThrownBy(() -> jwtService.validateAndParse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("A token signed with another secret is refused")
    void wrongSignature_refused() {
        var other = new JwtService("a-completely-different-secret-of-32-bytes!", FIFTEEN_MINUTES_MS);
        var token = other.issueAccessToken(7L, "a@b.test", "USER");

        assertThatThrownBy(() -> jwtService.validateAndParse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("A token whose payload was changed after signing is refused")
    void tamperedPayload_refused() {
        var forUser7 = jwtService.issueAccessToken(7L, "a@b.test", "USER").split("\\.");
        var forUser8 = jwtService.issueAccessToken(8L, "a@b.test", "ADMIN").split("\\.");
        var tampered = forUser7[0] + "." + forUser8[1] + "." + forUser7[2];

        assertThatThrownBy(() -> jwtService.validateAndParse(tampered)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("An unsigned token (alg: none) is refused")
    void unsigned_refused() {
        var unsigned = Jwts.builder().subject("7").claim("role", "ADMIN").compact();

        assertThat(unsigned).endsWith(".");
        assertThat(new String(Base64.getUrlDecoder().decode(unsigned.split("\\.")[0]), StandardCharsets.UTF_8)).contains("none");
        assertThatThrownBy(() -> jwtService.validateAndParse(unsigned)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("extractUserId returns the user id of a valid token")
    void extractUserId_valid() {
        assertThat(jwtService.extractUserId(jwtService.issueAccessToken(7L, "a@b.test", "USER"))).isEqualTo(7L);
    }

    @Test
    @DisplayName("extractUserId refuses an invalid token")
    void extractUserId_invalid() {
        assertThatThrownBy(() -> jwtService.extractUserId("x.y.z")).isInstanceOf(InvalidTokenException.class);
    }

    @ParameterizedTest(name = "{0} ms -> {1} s")
    @CsvSource({"900000, 900", "1500, 1", "999, 0"})
    @DisplayName("Access-token lifetime is reported in whole seconds, rounded down")
    void accessExpirySeconds_roundsDown(long millis, long seconds) {
        assertThat(new JwtService(SECRET, millis).getAccessExpirySeconds()).isEqualTo(seconds);
    }

    @Test
    @DisplayName("A secret shorter than 32 bytes is refused at start-up")
    void shortSecret_refused() {
        assertThatThrownBy(() -> new JwtService("too-short", FIFTEEN_MINUTES_MS)).isInstanceOf(WeakKeyException.class);
    }
}
