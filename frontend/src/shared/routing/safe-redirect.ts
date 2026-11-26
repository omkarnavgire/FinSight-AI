/**
 * Where to go after sign-in (T-66). Blocks open redirects.
 *
 * Only same-origin paths are allowed: they must start with one "/", contain no backslash or
 * control character, and not point back at the auth pages. Anything else falls back.
 */

export const DEFAULT_AFTER_LOGIN = '/dashboard'

const AUTH_PAGES = new Set(['/login', '/register'])
const PLACEHOLDER_ORIGIN = 'https://finsight.invalid'

/**
 * Returns a safe in-app path for `target`, or `fallback`.
 *
 * @param target untrusted value (usually from router state)
 * @param fallback path used when `target` is unsafe
 */
export function safeRedirect(target: unknown, fallback: string = DEFAULT_AFTER_LOGIN): string {
  if (typeof target !== 'string' || target.length > 2048) return fallback
  if (!target.startsWith('/') || target.startsWith('//')) return fallback
  if (target.includes('\\') || hasControlCharacter(target)) return fallback
  let url: URL
  try {
    url = new URL(target, PLACEHOLDER_ORIGIN)
  } catch {
    return fallback
  }
  if (url.origin !== PLACEHOLDER_ORIGIN || AUTH_PAGES.has(url.pathname)) return fallback
  return `${url.pathname}${url.search}${url.hash}`
}

/** Router state saved by `ProtectedRoute` when it sends a visitor to `/login`. */
export interface LoginRedirectState {
  from: string
}

/** Builds the router state that remembers where the visitor was going. */
export function loginRedirectState(location: { pathname: string; search: string; hash: string }): LoginRedirectState {
  return { from: `${location.pathname}${location.search}${location.hash}` }
}

/** Reads a safe target from untrusted router state. */
export function redirectTargetFromState(state: unknown): string {
  if (typeof state !== 'object' || state === null) return DEFAULT_AFTER_LOGIN
  return safeRedirect((state as Record<string, unknown>).from)
}

function hasControlCharacter(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i)
    if (code < 0x20 || code === 0x7f) return true
  }
  return false
}
