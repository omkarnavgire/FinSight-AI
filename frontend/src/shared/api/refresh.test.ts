import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { InvalidTokenResponseError, readAccessToken, refreshAccessToken, refreshClient } from './refresh'
import {
  isCrossTabLockSupported,
  REFRESH_LOCK_NAME,
  REFRESH_LOCK_TIMEOUT_MS,
  RefreshLockTimeoutError,
  withRefreshLock,
} from './refresh-lock'
import { clearAccessToken, getAccessToken, setAccessToken } from './auth-token'
import { mockAdapter, tokenBody } from '@/test/mock-adapter'

type LockCallback = () => Promise<string>
interface FakeLockOptions {
  mode: string
  signal: AbortSignal
}

/** Installs a fake `navigator.locks` and returns its `request` mock. */
function installLocks(request: (name: string, options: FakeLockOptions, callback: LockCallback) => Promise<string>) {
  const mock = vi.fn(request)
  Object.defineProperty(navigator, 'locks', { value: { request: mock }, configurable: true })
  return mock
}

function removeLocks(): void {
  Reflect.deleteProperty(navigator, 'locks')
}

/** A fake lock manager that grants one holder at a time, like the browser does. */
function serialLocks(): { request: ReturnType<typeof installLocks>; order: string[] } {
  const order: string[] = []
  let tail: Promise<unknown> = Promise.resolve()
  const request = installLocks((_name, _options, callback) => {
    const run = tail.then(async () => {
      order.push('acquire')
      try {
        return await callback()
      } finally {
        order.push('release')
      }
    })
    tail = run.catch(() => undefined)
    return run
  })
  return { request, order }
}

beforeEach(() => {
  clearAccessToken()
  removeLocks()
})

afterEach(() => {
  removeLocks()
  vi.useRealTimers()
})

describe('refreshAccessToken', () => {
  it('sends one request for concurrent callers and stores the token', async () => {
    let calls = 0
    refreshClient.defaults.adapter = mockAdapter(() => {
      calls++
      return { status: 200, body: tokenBody('fresh') }
    })

    const [a, b, c] = await Promise.all([refreshAccessToken(), refreshAccessToken(), refreshAccessToken()])

    expect([a, b, c]).toEqual(['fresh', 'fresh', 'fresh'])
    expect(calls).toBe(1)
    expect(getAccessToken()).toBe('fresh')
  })

  it('starts a new request after the previous one settled', async () => {
    let calls = 0
    refreshClient.defaults.adapter = mockAdapter(() => {
      calls++
      return { status: 200, body: tokenBody(`t${calls}`) }
    })
    expect(await refreshAccessToken()).toBe('t1')
    expect(await refreshAccessToken()).toBe('t2')
  })

  it('clears the token when the refresh fails', async () => {
    setAccessToken('old')
    refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
    await expect(refreshAccessToken()).rejects.toBeDefined()
    expect(getAccessToken()).toBeNull()
  })

  it('refuses a 200 response without a usable token', async () => {
    refreshClient.defaults.adapter = mockAdapter(() => ({ status: 200, body: '{"tokenType":"Bearer"}' }))
    await expect(refreshAccessToken()).rejects.toBeInstanceOf(InvalidTokenResponseError)
    expect(getAccessToken()).toBeNull()
  })

  it('holds the cross-tab lock for the whole HTTP call', async () => {
    const { request, order } = serialLocks()
    refreshClient.defaults.adapter = mockAdapter(() => {
      order.push('http')
      return { status: 200, body: tokenBody('fresh') }
    })

    await refreshAccessToken()

    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0]?.[0]).toBe(REFRESH_LOCK_NAME)
    expect(request.mock.calls[0]?.[1]).toMatchObject({ mode: 'exclusive' })
    expect(order).toEqual(['acquire', 'http', 'release'])
  })
})

describe('readAccessToken', () => {
  it('reads a valid TokenResponse', () => {
    expect(readAccessToken({ accessToken: 'x', tokenType: 'Bearer', expiresIn: 900 })).toBe('x')
  })

  it('refuses wrong shapes', () => {
    for (const bad of [null, 'x', {}, { accessToken: '', tokenType: 'Bearer' }, { accessToken: 'x', tokenType: 'Basic' }]) {
      expect(() => readAccessToken(bad)).toThrow(InvalidTokenResponseError)
    }
  })
})

describe('withRefreshLock', () => {
  it('runs the task directly and reports no cross-tab safety without Web Locks', async () => {
    expect(isCrossTabLockSupported()).toBe(false)
    await expect(withRefreshLock(() => Promise.resolve('t'))).resolves.toBe('t')
  })

  it('reports cross-tab safety when Web Locks exists', () => {
    installLocks((_n, _o, callback) => callback())
    expect(isCrossTabLockSupported()).toBe(true)
  })

  it('runs holders one after another (two tabs)', async () => {
    const { order } = serialLocks()
    const slow = (label: string) => async () => {
      order.push(`${label}:start`)
      await Promise.resolve()
      order.push(`${label}:end`)
      return label
    }
    await Promise.all([withRefreshLock(slow('A')), withRefreshLock(slow('B'))])
    expect(order).toEqual(['acquire', 'A:start', 'A:end', 'release', 'acquire', 'B:start', 'B:end', 'release'])
  })

  it('gives up after the timeout when another holder hangs', async () => {
    vi.useFakeTimers()
    installLocks(
      (_name, options) =>
        new Promise<string>((_resolve, reject) => {
          options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        }),
    )
    const pending = withRefreshLock(() => Promise.resolve('never'))
    const assertion = expect(pending).rejects.toBeInstanceOf(RefreshLockTimeoutError)
    await vi.advanceTimersByTimeAsync(REFRESH_LOCK_TIMEOUT_MS)
    await assertion
  })

  it('passes task errors through once the lock is granted', async () => {
    installLocks((_n, _o, callback) => callback())
    await expect(withRefreshLock(() => Promise.reject(new Error('boom')))).rejects.toThrow('boom')
  })
})
