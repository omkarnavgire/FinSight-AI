import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { Route, Routes, useLocation } from 'react-router'
import { ProtectedRoute } from './protected-route'
import { PublicOnlyRoute } from './public-only-route'
import { resetSessionRestore } from '../lib/restore-session'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { deferred, mockAdapter, tokenBody } from '@/test/mock-adapter'
import { userJwt } from '@/test/fake-jwt'
import { renderWithProviders, type RenderWithProvidersOptions } from '@/test/render-with-providers'

function LoginProbe() {
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? 'none'
  return <p>Login page, from: {from}</p>
}

function renderRoutes(route: RenderWithProvidersOptions['route']) {
  return renderWithProviders(
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginProbe />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route path="/dashboard" element={<p>Dashboard page</p>} />
        <Route path="/budgets" element={<p>Budgets page</p>} />
      </Route>
    </Routes>,
    { route },
  )
}

function signedIn(): void {
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 200, body: tokenBody(userJwt()) }))
}

function signedOut(): void {
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
})

describe('ProtectedRoute', () => {
  it('shows a loader while the session is restoring', async () => {
    const hold = deferred()
    refreshClient.defaults.adapter = mockAdapter(async () => {
      await hold.promise
      return { status: 401 }
    })
    renderRoutes('/budgets')
    expect(screen.getByRole('status')).toHaveTextContent('Restoring your session')
    expect(screen.queryByText('Budgets page')).not.toBeInTheDocument()
    hold.resolve()
    await screen.findByText(/Login page/)
  })

  it('sends a signed-out visitor to /login and remembers the page', async () => {
    signedOut()
    renderRoutes('/budgets?month=3#top')
    expect(await screen.findByText('Login page, from: /budgets?month=3#top')).toBeInTheDocument()
  })

  it('renders the page for a signed-in user', async () => {
    signedIn()
    renderRoutes('/budgets')
    expect(await screen.findByText('Budgets page')).toBeInTheDocument()
  })
})

describe('PublicOnlyRoute', () => {
  it('shows the login page to a signed-out visitor', async () => {
    signedOut()
    renderRoutes('/login')
    expect(await screen.findByText('Login page, from: none')).toBeInTheDocument()
  })

  it('sends a signed-in user to the dashboard', async () => {
    signedIn()
    renderRoutes('/login')
    expect(await screen.findByText('Dashboard page')).toBeInTheDocument()
  })

  it('sends a signed-in user back to the saved page', async () => {
    signedIn()
    renderRoutes({ pathname: '/login', state: { from: '/budgets' } })
    expect(await screen.findByText('Budgets page')).toBeInTheDocument()
  })

  it('ignores an unsafe saved target', async () => {
    signedIn()
    renderRoutes({ pathname: '/login', state: { from: '//evil.example/steal' } })
    expect(await screen.findByText('Dashboard page')).toBeInTheDocument()
  })
})
