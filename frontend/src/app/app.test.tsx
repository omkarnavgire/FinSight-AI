/**
 * Whole-app routing and auth flow (T-66, T-67) on the real route table. Only HTTP is faked.
 */
import { describe, expect, it } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import { getAccessToken } from '@/shared/api/auth-token'
import { NAV_ITEMS } from './layout/nav-items'
import { tokenBody } from '@/test/mock-adapter'
import { userJwt } from '@/test/fake-jwt'
import { renderApp, setupAppBackend } from '@/test/render-app'

const LOGIN_TOKEN = userJwt(7, 'demo@finsight.dev')

describe('App routes', () => {
  it('sends a signed-out visitor from a module page to login', async () => {
    setupAppBackend(false, () => ({ status: 200 }))
    const { router } = renderApp('/budgets')
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('redirects / to the dashboard for a signed-in user', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    const { router } = renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
  })

  it('sends a signed-in user away from the login page', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/login')
    expect(await screen.findByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument()
  })

  it('opens the real page for every sidebar link', async () => {
    setupAppBackend(true, () => ({ status: 500, body: '{"status":500}' }))
    const { router } = renderApp('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    for (const item of NAV_ITEMS) {
      fireEvent.click(within(nav).getByRole('link', { name: new RegExp(`^${item.label}`) }))
      expect(await screen.findByRole('heading', { name: item.label, level: 1 })).toBeInTheDocument()
      expect(router.state.location.pathname).toBe(item.path)
    }
    expect(screen.queryByText(/not built yet/)).not.toBeInTheDocument()
  })

  it('shows a not-found page for unknown paths', async () => {
    setupAppBackend(false, () => ({ status: 200 }))
    renderApp('/nope')
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})

describe('App auth flow', () => {
  it('logs in, returns to the asked page, navigates, and logs out', async () => {
    const backend = setupAppBackend(false, (config) =>
      config.url === '/auth/login' ? { status: 200, body: tokenBody(LOGIN_TOKEN) } : { status: 204 },
    )
    const { router } = renderApp('/budgets')

    fireEvent.change(await screen.findByLabelText(/^Email/), { target: { value: 'demo@finsight.dev' } })
    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: 'Demo#12345' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('heading', { name: 'Budgets', level: 1 })).toBeInTheDocument()
    expect(getAccessToken()).toBe(LOGIN_TOKEN)

    const nav = screen.getByRole('navigation', { name: 'Main' })
    fireEvent.click(within(nav).getByRole('link', { name: 'Dashboard' }))
    expect(await screen.findByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.getByText('You are signed out.')).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
    expect(router.state.location.pathname).toBe('/login')
    // The sidebar's fraud badge also calls /fraud/alerts once signed in; only auth calls are checked here.
    expect(backend.apiCalls.map((call) => call.url).filter((url) => url?.startsWith('/auth/'))).toEqual(['/auth/login', '/auth/logout'])
  })

  it('still signs out locally when the logout call fails', async () => {
    setupAppBackend(true, () => ({ status: 500, body: '{"status":500}' }))
    renderApp('/dashboard')
    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }))
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })

  it('sends the Bearer token on logout', async () => {
    const backend = setupAppBackend(true, () => ({ status: 204 }))
    renderApp('/dashboard')
    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }))
    await screen.findByRole('heading', { name: 'Log in' })
    const header = backend.apiCalls.find((call) => call.url === '/auth/logout')?.headers.get('Authorization')
    expect(header).toBe(`Bearer ${userJwt(7, 'demo@finsight.dev')}`)
  })
})
