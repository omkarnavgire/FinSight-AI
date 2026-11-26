import { describe, expect, it } from 'vitest'
import { readUserFromToken } from './jwt-claims'
import { fakeJwt, userJwt } from '@/test/fake-jwt'

describe('readUserFromToken', () => {
  it('reads sub, email and role', () => {
    expect(readUserFromToken(userJwt(42, 'demo@finsight.dev', 'ADMIN'))).toEqual({
      id: 42,
      email: 'demo@finsight.dev',
      role: 'ADMIN',
    })
  })

  it('decodes UTF-8 and base64url characters', () => {
    const token = fakeJwt({ sub: '1', email: 'zoë+ü?>>@example.com', role: 'USER' })
    expect(readUserFromToken(token)?.email).toBe('zoë+ü?>>@example.com')
  })

  it('returns null for malformed tokens', () => {
    for (const bad of ['', 'abc', 'a.b', 'a.b.c.d', 'x.!!!.y', `x.${btoa('not json')}.y`, `x.${btoa('"str"')}.y`]) {
      expect(readUserFromToken(bad), bad).toBeNull()
    }
  })

  it('returns null when claims are missing or wrong', () => {
    const cases = [
      { email: 'a@b.c', role: 'USER' },
      { sub: '1', role: 'USER' },
      { sub: '1', email: 'a@b.c' },
      { sub: 1, email: 'a@b.c', role: 'USER' },
      { sub: 'abc', email: 'a@b.c', role: 'USER' },
      { sub: '-1', email: 'a@b.c', role: 'USER' },
      { sub: '1234567890123456', email: 'a@b.c', role: 'USER' },
    ]
    for (const claims of cases) {
      expect(readUserFromToken(fakeJwt(claims)), JSON.stringify(claims)).toBeNull()
    }
  })
})
