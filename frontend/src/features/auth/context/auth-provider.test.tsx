import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import { useAuth } from '../hooks/use-auth'
import { resetSessionRestore } from '../lib/restore-session'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken, getAccessToken } from '@/shared/api/auth-token'
import { notifySessionExpired } from '@/shared/api/auth-events'
import { deferred, mockAdapter, tokenBody } from '@/test/mock-adapter'
import { userJwt } from '@/test/fake-jwt'
import { FakeChannel, sendFromOtherTab } from '@/test/fake-broadcast-channel'
import { renderWithProviders } from '@/test/render-with-providers'

let refreshCalls: number

function useRefresh(reply: () => { status: number; body?: string } | Promise<{ status: number; body?: string }>): void {
  refreshClient.defaults.adapter = mockAdapter(() => {
    refreshCalls++
    return reply()
  })
}

const ok = () => ({ status: 200, body: tokenBody(userJwt(7, 'demo@finsight.dev')) })
const unauthorized = () => ({ status: 401, body: '{"status":401}' })

function Probe() {
  const auth = useAuth()
  return (
    <div>
      <p>status: {auth.status}</p>
      <p>email: {auth.user?.email ?? 'none'}</p>
      <p>reason: {auth.endReason ?? 'none'}</p>
      <p>cross-tab: {auth.crossTabSafe ? 'safe' : 'unsafe'}</p>
      <button type="button" onClick={() => auth.establishSession(userJwt(9, 'new@finsight.dev'))}>
        sign in
      </button>
      <button type="button" onClick={auth.endSession}>
        sign out
      </button>
    </div>
  )
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshCalls = 0
  vi.stubGlobal('BroadcastChannel', FakeChannel)
})

afterEach(() => {
  vi.unstubAllGlobals()
  FakeChannel.reset()
  Reflect.deleteProperty(navigator, 'locks')
})

describe('AuthProvider — session restore', () => {
  it('starts as restoring, then signs in from the refresh cookie', async () => {
    const hold = deferred()
    useRefresh(async () => {
      await hold.promise
      return ok()
    })
    renderWithProviders(<Probe />)

    expect(screen.getByText('status: restoring')).toBeInTheDocument()
    hold.resolve()
    expect(await screen.findByText('status: authenticated')).toBeInTheDocument()
    expect(screen.getByText('email: demo@finsight.dev')).toBeInTheDocument()
    expect(getAccessToken()).toBe(userJwt(7, 'demo@finsight.dev'))
  })

  it('B-8: StrictMode double mount sends exactly one refresh', async () => {
    useRefresh(ok)
    renderWithProviders(<Probe />, { strict: true })
    expect(await screen.findByText('status: authenticated')).toBeInTheDocument()
    expect(refreshCalls).toBe(1)
  })

  it('becomes anonymous without a reason when there is no valid cookie', async () => {
    useRefresh(unauthorized)
    renderWithProviders(<Probe />)
    expect(await screen.findByText('status: anonymous')).toBeInTheDocument()
    expect(screen.getByText('reason: none')).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })
})

describe('AuthProvider — ending a session', () => {
  it('handles session expiry: clears token and cached data', async () => {
    useRefresh(ok)
    const { queryClient } = renderWithProviders(<Probe />)
    await screen.findByText('status: authenticated')
    queryClient.setQueryData(['accounts'], ['secret'])

    act(() => notifySessionExpired())

    expect(screen.getByText('status: anonymous')).toBeInTheDocument()
    expect(screen.getByText('reason: expired')).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined()
  })

  it('signs out, clears data and tells other tabs', async () => {
    useRefresh(ok)
    const { queryClient } = renderWithProviders(<Probe />)
    await screen.findByText('status: authenticated')
    queryClient.setQueryData(['budgets'], ['secret'])

    act(() => screen.getByRole('button', { name: 'sign out' }).click())

    expect(screen.getByText('reason: logout')).toBeInTheDocument()
    expect(queryClient.getQueryData(['budgets'])).toBeUndefined()
    expect(FakeChannel.sent).toContainEqual(expect.objectContaining({ type: 'logout' }))
  })

  it('signs out when another tab logs out', async () => {
    useRefresh(ok)
    renderWithProviders(<Probe />)
    await screen.findByText('status: authenticated')

    act(() => sendFromOtherTab('logout'))

    expect(screen.getByText('reason: remote-logout')).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })
})

describe('AuthProvider — signing in', () => {
  it('establishes a session from a login token and tells other tabs', async () => {
    useRefresh(unauthorized)
    renderWithProviders(<Probe />)
    await screen.findByText('status: anonymous')

    act(() => screen.getByRole('button', { name: 'sign in' }).click())

    expect(screen.getByText('email: new@finsight.dev')).toBeInTheDocument()
    expect(getAccessToken()).toBe(userJwt(9, 'new@finsight.dev'))
    expect(FakeChannel.sent).toContainEqual(expect.objectContaining({ type: 'login' }))
  })

  it('picks up a login from another tab with one refresh', async () => {
    let signedInElsewhere = false
    useRefresh(() => (signedInElsewhere ? ok() : unauthorized()))
    renderWithProviders(<Probe />)
    await screen.findByText('status: anonymous')

    signedInElsewhere = true
    act(() => sendFromOtherTab('login'))

    expect(await screen.findByText('status: authenticated')).toBeInTheDocument()
    expect(refreshCalls).toBe(2)
  })

  it('ignores a login event while already signed in', async () => {
    useRefresh(ok)
    renderWithProviders(<Probe />)
    await screen.findByText('status: authenticated')

    act(() => sendFromOtherTab('login'))

    await waitFor(() => expect(refreshCalls).toBe(1))
  })
})

describe('AuthProvider — cross-tab safety flag', () => {
  it('reports unsafe without Web Locks', async () => {
    useRefresh(unauthorized)
    renderWithProviders(<Probe />)
    expect(await screen.findByText('cross-tab: unsafe')).toBeInTheDocument()
  })

  it('reports safe with Web Locks', async () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: (_name: string, _options: unknown, callback: () => Promise<string>) => callback() },
      configurable: true,
    })
    useRefresh(ok)
    renderWithProviders(<Probe />)
    expect(await screen.findByText('cross-tab: safe')).toBeInTheDocument()
  })
})
