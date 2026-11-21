package com.finsight.shared.security;

import com.finsight.auth.filter.JwtAuthFilter;
import com.finsight.auth.filter.OriginRefererFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the FinSight API.
 *
 * <p>Filter chain order (per request):
 * <ol>
 *   <li>CORS</li>
 *   <li>{@link OriginRefererFilter} — CSRF second layer for auth endpoints</li>
 *   <li>{@link JwtAuthFilter} — validates Bearer token and sets SecurityContext</li>
 *   <li>{@code UsernamePasswordAuthenticationFilter}</li>
 *   <li>Method-level {@code @PreAuthorize}</li>
 * </ol>
 *
 * <p>Sessions are stateless — no {@code HttpSession} is created.
 * CSRF token protection is disabled because:
 * <ul>
 *   <li>Access tokens travel in {@code Authorization} headers (not cookies) — inherently CSRF-safe.</li>
 *   <li>The refresh cookie is {@code SameSite=Strict} — browsers block cross-site attachment.</li>
 *   <li>{@link OriginRefererFilter} provides a second layer for the auth endpoints specifically.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final OriginRefererFilter originRefererFilter;
    private final List<String> allowedOrigins;
    private final boolean requireHttps;
    private final long hstsMaxAgeSeconds;
    private final boolean hstsIncludeSubDomains;
    private final String publicHost;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            OriginRefererFilter originRefererFilter,
            @Value("${finsight.cors.allowed-origins:}") String allowedOrigins,
            @Value("${finsight.security.require-https:true}") boolean requireHttps,
            @Value("${finsight.security.hsts.max-age-seconds:31536000}") long hstsMaxAgeSeconds,
            @Value("${finsight.security.hsts.include-subdomains:true}") boolean hstsIncludeSubDomains,
            @Value("${finsight.security.public-host:}") String publicHost) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.originRefererFilter = originRefererFilter;
        this.allowedOrigins = parseOrigins(allowedOrigins);
        this.requireHttps = requireHttps;
        this.hstsMaxAgeSeconds = hstsMaxAgeSeconds;
        this.hstsIncludeSubDomains = hstsIncludeSubDomains;
        this.publicHost = publicHost;
    }

    /**
     * Exact, trimmed origins. A wildcard is refused in every profile (T-62); non-local profiles are
     * further limited to exact https origins by {@link ProductionSecurityValidator}.
     */
    static List<String> parseOrigins(String raw) {
        var origins = Arrays.stream(raw == null ? new String[0] : raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("finsight.cors.allowed-origins must not contain a wildcard");
        }
        return origins;
    }

    /**
     * Defines the security filter chain.
     *
     * <p>Public endpoints: {@code /api/v1/auth/register}, {@code /api/v1/auth/login},
     * {@code /api/v1/auth/refresh}, and Actuator health.
     * Everything else requires authentication.
     *
     * <p>When {@code finsight.security.require-https} is on (every non-local profile), plain HTTP is
     * redirected to HTTPS with 308 ({@link HttpsEnforcementFilter}). HSTS is sent on HTTPS responses
     * only; Spring Security never writes it on plain HTTP.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (requireHttps) {
            http.addFilterBefore(new HttpsEnforcementFilter(publicHost), SecurityContextHolderFilter.class);
        }
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(hstsIncludeSubDomains)
                        .maxAgeInSeconds(hstsMaxAgeSeconds)))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh"
                        ).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                // J-7: unauthenticated -> 401 ProblemDetail. Access-denied handling (403) is left at the default.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint()))
                .addFilterBefore(originRefererFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * BCrypt password encoder, strength 12 (as specified in design.md).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Exposes the {@link AuthenticationManager} as a bean for use in {@code AuthService}.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Per-profile CORS configuration (T-62, NFR-01-5). Exact origins only, from
     * {@code finsight.cors.allowed-origins} (comma-separated); no pattern matching, no wildcard.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
