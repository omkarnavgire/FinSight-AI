package com.finsight.auth.filter;

import com.finsight.auth.service.JwtService;
import com.finsight.shared.exception.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the {@code Authorization: Bearer} header, validates the JWT, and populates
 * the {@link SecurityContextHolder} for the duration of the request.
 *
 * <p>Runs before {@code UsernamePasswordAuthenticationFilter} in the Spring Security
 * filter chain (registered in {@link com.finsight.shared.security.SecurityConfig}).
 *
 * <p>If the header is absent or the token is invalid, the filter proceeds without
 * setting authentication — downstream {@code @PreAuthorize} annotations will then
 * reject the request with 401.
 *
 * <p>Token values are NEVER logged.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        var authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        var token = authHeader.substring(7);

        try {
            var claims = jwtService.validateAndParse(token);
            var userId = Long.parseLong(claims.getSubject());
            var role = claims.get("role", String.class);

            var authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (InvalidTokenException ignored) {
            // Invalid token — proceed without authentication; @PreAuthorize will reject.
        }

        filterChain.doFilter(request, response);
    }
}
