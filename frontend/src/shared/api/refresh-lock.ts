/**
 * Cross-tab refresh lock (D-1, D-2).
 *
 * The backend rotates the refresh token on every `/auth/refresh` and treats a second use of
 * the old token as theft (all sessions revoked). Tabs share one cookie, so two tabs must never
 * refresh at the same time.
 *
 * With the Web Locks API, all tabs of this origin queue on one exclusive lock. The browser
 * stores the rotated cookie before the response resolves, so the next tab sends the new cookie.
 *
 * Without Web Locks (old browsers, plain-HTTP LAN hosts), only the in-tab single-flight in
 * `refresh.ts` applies. Tabs are then NOT coordinated: two tabs refreshing together can trigger
 * reuse detection and sign the user out everywhere. The UI warns about this
 * ({@link isCrossTabLockSupported}).
 */

export const REFRESH_LOCK_NAME = 'finsight-auth-refresh'

/** How long a tab waits for another tab's refresh before giving up. */
export const REFRESH_LOCK_TIMEOUT_MS = 10_000

/** Thrown when the lock was not granted in time (another tab hung while refreshing). */
export class RefreshLockTimeoutError extends Error {
  constructor() {
    super('Timed out waiting for another tab to refresh the session')
    this.name = 'RefreshLockTimeoutError'
  }
}

/** True when refreshes can be coordinated across tabs. */
export function isCrossTabLockSupported(): boolean {
  return typeof navigator !== 'undefined' && 'locks' in navigator && typeof navigator.locks?.request === 'function'
}

/**
 * Runs a refresh while holding the cross-tab lock, or directly when Web Locks is missing.
 *
 * @param task the refresh call; resolves to the new access token
 * @returns the task's result
 * @throws RefreshLockTimeoutError if the lock is not granted within {@link REFRESH_LOCK_TIMEOUT_MS}
 */
export async function withRefreshLock(task: () => Promise<string>): Promise<string> {
  if (!isCrossTabLockSupported()) {
    return task()
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REFRESH_LOCK_TIMEOUT_MS)
  let granted = false
  try {
    const token: string = await navigator.locks.request(
      REFRESH_LOCK_NAME,
      { mode: 'exclusive', signal: controller.signal },
      () => {
        granted = true
        clearTimeout(timer)
        return task()
      },
    )
    return token
  } catch (error) {
    if (!granted && controller.signal.aborted) {
      throw new RefreshLockTimeoutError()
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}
