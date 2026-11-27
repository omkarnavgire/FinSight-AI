/**
 * Reads display data from an access token (D-4 in the Stage A plan: no `/me` endpoint).
 *
 * The signature is NOT checked here: this is for showing the user's email only. The backend
 * verifies every token; the frontend never makes access decisions from these claims.
 */

export interface AuthUser {
  id: number
  email: string
  role: string
}

/**
 * Decodes the `sub`, `email` and `role` claims.
 *
 * @param token compact JWT
 * @returns the claims, or `null` if the token cannot be read
 */
export function readUserFromToken(token: string): AuthUser | null {
  const parts = token.split('.')
  if (parts.length !== 3 || parts[1] === undefined) return null
  try {
    const payload: unknown = JSON.parse(decodeBase64Url(parts[1]))
    if (typeof payload !== 'object' || payload === null) return null
    const { sub, email, role } = payload as Record<string, unknown>
    const id = typeof sub === 'string' && /^\d{1,15}$/.test(sub) ? Number(sub) : Number.NaN
    if (!Number.isSafeInteger(id) || typeof email !== 'string' || typeof role !== 'string') return null
    return { id, email, role }
  } catch {
    return null
  }
}

function decodeBase64Url(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
  const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}
