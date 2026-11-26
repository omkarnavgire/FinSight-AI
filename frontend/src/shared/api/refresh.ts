/**
 * Single-flight access-token refresh (T-64, AC-SF-1, D-1, D-2).
 *
 * - In this tab, concurrent callers share one `refreshPromise`, so one `/auth/refresh` is sent.
 * - Across tabs, the call runs inside the Web Lock from `refresh-lock.ts` when available.
 * - The call uses `refreshClient`, which has no interceptors, so a 401 from `/auth/refresh`
 *   can never start another refresh or wait on itself.
 *
 * On success the new token is stored in memory. On any failure the token is cleared.
 */
import { createApiInstance } from './api-instance'
import { clearAccessToken, setAccessToken } from './auth-token'
import { withRefreshLock } from './refresh-lock'

/** Bare client for `/auth/refresh` only. Never add interceptors to it. */
export const refreshClient = createApiInstance()

/** Thrown when `/auth/refresh` answers 2xx without a usable access token. */
export class InvalidTokenResponseError extends Error {
  constructor() {
    super('Refresh response did not contain an access token')
    this.name = 'InvalidTokenResponseError'
  }
}

let refreshPromise: Promise<string> | null = null

/**
 * Refreshes the access token once, however many callers ask at the same time.
 *
 * @returns the new access token
 */
export function refreshAccessToken(): Promise<string> {
  refreshPromise ??= withRefreshLock(requestNewToken)
    .catch((error: unknown) => {
      clearAccessToken()
      throw error
    })
    .finally(() => {
      refreshPromise = null
    })
  return refreshPromise
}

async function requestNewToken(): Promise<string> {
  const response = await refreshClient.post<unknown>('/auth/refresh')
  const token = readAccessToken(response.data)
  setAccessToken(token)
  return token
}

/**
 * Reads `accessToken` from a `TokenResponse` body.
 *
 * @param body parsed response body
 * @returns the token
 * @throws InvalidTokenResponseError when the shape is wrong
 */
export function readAccessToken(body: unknown): string {
  if (typeof body !== 'object' || body === null) throw new InvalidTokenResponseError()
  const { accessToken, tokenType } = body as Record<string, unknown>
  if (typeof accessToken !== 'string' || accessToken === '' || tokenType !== 'Bearer') {
    throw new InvalidTokenResponseError()
  }
  return accessToken
}
