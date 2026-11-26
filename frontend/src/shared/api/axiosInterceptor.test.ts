/**
 * axiosInterceptor.test.ts (T-64b)
 *
 * Single-flight 401 refresh:
 *   AC-SF-1: 3 concurrent 401 responses produce exactly 1 /auth/refresh call.
 *   AC-SF-2: All 3 requests are retried with the new token after refresh resolves.
 *   AC-SF-3: If refresh fails, all 3 requests reject and the user is redirected to /login.
 * Plus the defects fixed in Phase 11 (B-1 to B-4) and lossless JSON transport.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { apiClient } from './client'
import { refreshClient } from './refresh'
import { clearAccessToken, getAccessToken, setAccessToken } from './auth-token'
import { setSessionExpiredHandler } from './auth-events'
import { decimalToJson } from './lossless-json'
import { toDecimal } from '@/shared/money/decimal'
import { authHeader, deferred, type MockHandler, mockAdapter, networkError, tokenBody } from '@/test/mock-adapter'

const OK = { status: 200, body: '{"ok":true}' }
const UNAUTHORIZED = { status: 401, body: '{"status":401,"title":"Unauthorized","detail":"Authentication required"}' }

let sessionExpired: ReturnType<typeof vi.fn>
let refreshCalls: number
let apiCalls: InternalAxiosRequestConfig[]

function useBackend(api: MockHandler, refresh: MockHandler): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    apiCalls.push(config)
    return api(config)
  })
  refreshClient.defaults.adapter = mockAdapter((config) => {
    refreshCalls++
    return refresh(config)
  })
}

/** API that accepts only `Bearer new`. */
const acceptsNewToken: MockHandler = (config) => (authHeader(config) === 'Bearer new' ? OK : UNAUTHORIZED)

beforeEach(() => {
  clearAccessToken()
  refreshCalls = 0
  apiCalls = []
  sessionExpired = vi.fn()
  setSessionExpiredHandler(sessionExpired)
})

afterEach(() => {
  setSessionExpiredHandler(null)
})

describe('Axios interceptor — single-flight 401 refresh', () => {
  it('AC-SF-1: 3 concurrent 401s produce exactly 1 /auth/refresh call', async () => {
    setAccessToken('old')
    const allFailed = deferred()
    let unauthorized = 0
    useBackend(
      (config) => {
        if (authHeader(config) === 'Bearer new') return OK
        unauthorized++
        if (unauthorized === 3) allFailed.resolve()
        return UNAUTHORIZED
      },
      async () => {
        await allFailed.promise
        return { status: 200, body: tokenBody('new') }
      },
    )

    await Promise.all([apiClient.get('/accounts'), apiClient.get('/budgets'), apiClient.get('/transactions')])

    expect(refreshCalls).toBe(1)
  })

  it('AC-SF-2: all 3 requests are retried with the new token', async () => {
    setAccessToken('old')
    useBackend(acceptsNewToken, () => ({ status: 200, body: tokenBody('new') }))

    const results = await Promise.all([apiClient.get('/a'), apiClient.get('/b'), apiClient.get('/c')])

    expect(results.map((r) => r.data)).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    const retried = apiCalls.filter((c) => authHeader(c) === 'Bearer new').map((c) => c.url)
    expect(retried.sort()).toEqual(['/a', '/b', '/c'])
    expect(getAccessToken()).toBe('new')
  })

  it('AC-SF-3: refresh failure rejects all 3 requests and redirects to /login once', async () => {
    setAccessToken('old')
    useBackend(acceptsNewToken, () => UNAUTHORIZED)

    const results = await Promise.allSettled([apiClient.get('/a'), apiClient.get('/b'), apiClient.get('/c')])

    expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected', 'rejected'])
    expect(refreshCalls).toBe(1)
    expect(sessionExpired).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
  })

  it('B-1: a 401 from /auth/refresh settles instead of hanging', async () => {
    useBackend(() => UNAUTHORIZED, () => UNAUTHORIZED)
    await expect(apiClient.get('/accounts')).rejects.toMatchObject({ response: { status: 401 } })
    expect(refreshCalls).toBe(1)
  })

  it('B-2: a 401 from /auth/login does not refresh or redirect', async () => {
    useBackend(() => UNAUTHORIZED, () => ({ status: 200, body: tokenBody('new') }))
    await expect(apiClient.post('/auth/login', { email: 'a@b.c', password: 'x' })).rejects.toMatchObject({
      response: { status: 401 },
    })
    expect(refreshCalls).toBe(0)
    expect(sessionExpired).not.toHaveBeenCalled()
  })

  it('B-2: a 401 from /auth/register does not refresh', async () => {
    useBackend(() => UNAUTHORIZED, () => ({ status: 200, body: tokenBody('new') }))
    await expect(apiClient.post('/auth/register', {})).rejects.toBeDefined()
    expect(refreshCalls).toBe(0)
  })

  it('B-4: retries with a token another call already refreshed, without a new refresh', async () => {
    setAccessToken('old')
    useBackend(
      (config) => {
        if (authHeader(config) === 'Bearer old') {
          setAccessToken('new') // another request refreshed meanwhile
          return UNAUTHORIZED
        }
        return acceptsNewToken(config)
      },
      () => ({ status: 200, body: tokenBody('other') }),
    )

    await expect(apiClient.get('/accounts')).resolves.toMatchObject({ data: { ok: true } })
    expect(refreshCalls).toBe(0)
  })

  it('retries at most once', async () => {
    setAccessToken('old')
    useBackend(() => UNAUTHORIZED, () => ({ status: 200, body: tokenBody('new') }))
    await expect(apiClient.get('/accounts')).rejects.toMatchObject({ response: { status: 401 } })
    expect(apiCalls).toHaveLength(2)
    expect(refreshCalls).toBe(1)
    expect(sessionExpired).not.toHaveBeenCalled()
  })

  it('does not refresh on other errors', async () => {
    setAccessToken('old')
    useBackend(() => ({ status: 403, body: '{"status":403}' }), () => ({ status: 200, body: tokenBody('new') }))
    await expect(apiClient.get('/accounts')).rejects.toMatchObject({ response: { status: 403 } })
    expect(refreshCalls).toBe(0)
  })

  it('passes network errors through', async () => {
    useBackend(networkError, () => ({ status: 200, body: tokenBody('new') }))
    await expect(apiClient.get('/accounts')).rejects.toMatchObject({ code: 'ERR_NETWORK' })
    expect(refreshCalls).toBe(0)
  })
})

describe('Axios client — headers and transport', () => {
  it('attaches the in-memory token as a Bearer header', async () => {
    setAccessToken('abc')
    useBackend(() => OK, () => UNAUTHORIZED)
    await apiClient.get('/accounts')
    expect(authHeader(apiCalls[0] as InternalAxiosRequestConfig)).toBe('Bearer abc')
  })

  it('sends no Authorization header when signed out', async () => {
    useBackend(() => OK, () => UNAUTHORIZED)
    await apiClient.get('/accounts')
    expect(authHeader(apiCalls[0] as InternalAxiosRequestConfig)).toBeNull()
  })

  it('sends cookies (withCredentials) on both clients', () => {
    expect(apiClient.defaults.withCredentials).toBe(true)
    expect(refreshClient.defaults.withCredentials).toBe(true)
  })

  it('has no interceptors on the refresh client', () => {
    const handlers = (refreshClient.interceptors.response as unknown as { handlers: unknown[] }).handlers
    expect(handlers.filter((h) => h !== null)).toHaveLength(0)
  })

  it('reads response money as exact text', async () => {
    useBackend(() => ({ status: 200, body: '{"balance":90071992547409.93,"id":5}' }), () => UNAUTHORIZED)
    const response = await apiClient.get('/accounts/5')
    expect(response.data).toEqual({ balance: '90071992547409.93', id: 5 })
  })

  it('writes request money verbatim', async () => {
    useBackend(() => ({ status: 201, body: '{}' }), () => UNAUTHORIZED)
    await apiClient.post('/transactions', { accountId: 1, amount: decimalToJson(toDecimal('-9999999999999.99')) })
    expect(apiCalls[0]?.data).toBe('{"accountId":1,"amount":-9999999999999.99}')
  })

  it('treats an empty 204 body as undefined', async () => {
    useBackend(() => ({ status: 204 }), () => UNAUTHORIZED)
    const response = await apiClient.delete('/accounts/5')
    expect(response.data).toBeUndefined()
  })
})
