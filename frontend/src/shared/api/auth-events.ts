/**
 * Session-expired signal (T-64, AC-SF-3).
 *
 * The Axios interceptor calls {@link notifySessionExpired} once per failed refresh. The auth
 * provider registers a handler that clears app state and routes to `/login`. Without a
 * handler (e.g. before the app mounts) the browser is sent to `/login` directly.
 */

export type SessionExpiredHandler = () => void

let handler: SessionExpiredHandler | null = null

/** Registers the handler, or removes it with `null`. */
export function setSessionExpiredHandler(next: SessionExpiredHandler | null): void {
  handler = next
}

/** Reports that the session could not be refreshed. */
export function notifySessionExpired(): void {
  if (handler === null) {
    window.location.assign('/login')
    return
  }
  handler()
}
