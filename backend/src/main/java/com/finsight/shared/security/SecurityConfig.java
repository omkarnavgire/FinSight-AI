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
    private final String allowedOrigins;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            OriginRefererFilter originRefererFilter,
            @Value("${finsight.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.originRefererFilter = originRefererFilter;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Defines the security filter chain.
     *
     * <p>Public endpoints: {@code /api/v1/auth/register}, {@code /api/v1/auth/login},
     * {@code /api/v1/auth/refresh}, and Actuator health.
     * Everything else requires authentication.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
     * Per-profile CORS configuration. Allowed origins come from
     * {@code finsight.cors.allowed-origins} (comma-separated).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
