/** Unsigned JWT-shaped strings for tests. Never valid on the backend. */

function base64Url(text: string): string {
  let binary = ''
  for (const byte of new TextEncoder().encode(text)) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** A token with the given payload claims and a dummy signature. */
export function fakeJwt(claims: Record<string, unknown>): string {
  return `${base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${base64Url(JSON.stringify(claims))}.sig`
}

/** A token shaped like the backend's access token. */
export function userJwt(id = 7, email = 'demo@finsight.dev', role = 'USER'): string {
  return fakeJwt({ sub: String(id), email, role, iat: 1_700_000_000, exp: 1_700_000_900 })
}
