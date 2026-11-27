/**
 * The access token, held in memory only (T-64).
 *
 * Never written to localStorage, sessionStorage, cookies or the URL. A page reload drops it;
 * the session is then restored from the HttpOnly refresh cookie via `/auth/refresh`.
 */

let accessToken: string | null = null

/** The current access token, or `null` when signed out. */
export function getAccessToken(): string | null {
  return accessToken
}

/** Stores a new access token in memory. */
export function setAccessToken(token: string): void {
  accessToken = token
}

/** Forgets the access token. */
export function clearAccessToken(): void {
  accessToken = null
}
