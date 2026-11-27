/**
 * Restores the session once per page load (T-66, B-7, B-8).
 *
 * The access token lives in memory, so a reload loses it. The HttpOnly refresh cookie is used
 * to get a new one. The promise is memoised at module level: React StrictMode mounts effects
 * twice in development, and both mounts must share one `/auth/refresh` call. A second call
 * with the same cookie would look like token theft to the backend.
 */
import { refreshAccessToken } from '@/shared/api/refresh'

let restorePromise: Promise<string | null> | null = null

/**
 * Tries the refresh cookie once.
 *
 * @returns the new access token, or `null` when there is no valid session
 */
export function restoreSession(): Promise<string | null> {
  restorePromise ??= refreshAccessToken().catch(() => null)
  return restorePromise
}

/** Forgets the memo so the next `restoreSession()` asks again (after login, logout or a remote event). */
export function resetSessionRestore(): void {
  restorePromise = null
}
