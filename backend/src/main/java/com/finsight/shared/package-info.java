/**
 * Cross-cutting shared infrastructure for FinSight AI.
 *
 * <h2>Packages</h2>
 * <ul>
 *   <li>{@code shared/advice} — {@code @RestControllerAdvice} with RFC 7807 ProblemDetail mappings</li>
 *   <li>{@code shared/country} — ISO 3166-1 alpha-2 allowlist ({@code CountryCodes})</li>
 *   <li>{@code shared/exception} — base exceptions ({@code FinSightException},
 *       {@code NotFoundException}) and their concrete subclasses</li>
 *   <li>{@code shared/ownership} — {@code SecurityUtils.currentUserId()} for mandatory
 *       user-identity extraction from the Spring Security context</li>
 *   <li>{@code shared/security} — {@code SecurityConfig}, {@code RateLimiter} interface</li>
 * </ul>
 *
 * <h2>Mandatory ownership pattern (AC-OWN-1 through AC-OWN-3)</h2>
 *
 * <p>Every service method that reads or writes user-scoped data MUST follow this pattern:
 *
 * <pre>{@code
 * // 1. Extract userId from SecurityContext — NEVER from request body or path variable.
 * Long userId = SecurityUtils.currentUserId();
 *
 * // 2. Query with both id AND userId — the query returns empty for both
 * //    "not found" and "belongs to a different user".
 * Entity entity = repository.findByIdAndUserId(id, userId)
 *         .orElseThrow(() -> new XxxNotFoundException(id));
 *
 * // 3. XxxNotFoundException extends NotFoundException → maps to HTTP 404.
 * //    404 (not 403) prevents leaking the existence of another user's resource.
 * }</pre>
 *
 * <p>Controllers must carry {@code @PreAuthorize("isAuthenticated()")} on every method.
 * No controller ever passes a userId from the URL or request body to a service.
 *
 * <h2>Module boundary rule (AC-FK-2, enforced by ModuleBoundaryTest)</h2>
 *
 * <p>No module's service or controller may import another module's {@code repository}
 * package. Cross-module data access goes through a named service interface only.
 */
package com.finsight.shared;
