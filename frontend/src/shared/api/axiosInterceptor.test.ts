/**
 * axiosInterceptor.test.ts
 *
 * Tests for the single-flight 401 refresh pattern (AC-SF-1, AC-SF-2, AC-SF-3).
 *
 * These tests are stubs for Phase 1 and will be fully implemented in T-64b
 * (Phase 9 — Frontend API layer).
 *
 * Acceptance criteria:
 *   AC-SF-1: 3 concurrent 401 responses produce exactly 1 /auth/refresh call.
 *   AC-SF-2: All 3 requests are retried with the new token after refresh resolves.
 *   AC-SF-3: If refresh fails, all 3 requests reject and the user is redirected to /login.
 */
import { describe, it } from 'vitest'

describe('Axios interceptor — single-flight 401 refresh', () => {
  it.todo('AC-SF-1: 3 concurrent 401s produce exactly 1 /auth/refresh call')
  it.todo('AC-SF-2: all 3 requests are retried with the new token')
  it.todo('AC-SF-3: refresh failure rejects all 3 requests and redirects to /login')
})
