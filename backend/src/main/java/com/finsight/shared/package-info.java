/**
 * Shared cross-cutting utilities — exceptions, global advice, security filters,
 * pagination wrapper, country codes, and dev-only data seeder.
 *
 * <p><strong>No module may import another module's {@code repository} package.</strong>
 * Cross-module access goes through a service interface defined here or in the
 * target module's public API. This rule is enforced by {@code ModuleBoundaryTest}.
 */
package com.finsight.shared;
