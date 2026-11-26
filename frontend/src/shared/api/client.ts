/**
 * Central Axios instance for all API calls (T-64).
 *
 * Rules (coding standards, architecture):
 * - All API calls go through `apiClient`; never call `fetch` in a component.
 * - The access token is attached from memory (`auth-token.ts`); it is never persisted.
 * - The refresh token is an HttpOnly cookie that only the browser sends; JS never sees it.
 *
 * 401 handling (single-flight, AC-SF-1 to AC-SF-3):
 * 1. A 401 from `/auth/login`, `/auth/register` or `/auth/refresh` is returned as-is.
 *    (Wrong credentials are not an expired session.)
 * 2. If another call already replaced the token after this request was sent, the request is
 *    retried with the current token, without a new refresh.
 * 3. Otherwise all concurrent 401s share one `refreshAccessToken()`; on success each request
 *    is retried once with the new token.
 * 4. If the refresh fails, every waiting request rejects with its original error and the
 *    session-expired handler runs once (routes to `/login`).
 */
import axios, { type InternalAxiosRequestConfig } from 'axios'
import { createApiInstance } from './api-instance'
import { getAccessToken } from './auth-token'
import { notifySessionExpired } from './auth-events'
import { refreshAccessToken } from './refresh'

export { API_BASE_URL } from './api-instance'

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  /** Set once the request has been retried after a 401. */
  _retry?: boolean
  /** The access token this request was sent with (`null` = none). */
  _sentToken?: string | null
}

/** Endpoints whose 401 means "bad credentials or cookie", not "expired access token". */
const NO_REFRESH_PATH = /^\/?auth\/(?:login|register|refresh)(?:[?#]|$)/

/** Refresh promises whose failure has already been reported, so it is reported once. */
const reportedFailures = new WeakSet<Promise<string>>()

export const apiClient = createApiInstance()

apiClient.interceptors.request.use((config: RetryableRequestConfig) => {
  const token = getAccessToken()
  config._sentToken = token
  if (token === null) {
    config.headers.delete('Authorization')
  } else {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error) || error.config === undefined) {
      throw error
    }
    const config: RetryableRequestConfig = error.config
    if (error.response?.status !== 401 || config._retry === true || NO_REFRESH_PATH.test(config.url ?? '')) {
      throw error
    }
    config._retry = true

    const current = getAccessToken()
    if (current !== null && current !== config._sentToken) {
      return apiClient(config)
    }

    const pending = refreshAccessToken()
    try {
      await pending
    } catch {
      if (!reportedFailures.has(pending)) {
        reportedFailures.add(pending)
        notifySessionExpired()
      }
      throw error
    }
    return apiClient(config)
  },
)
