/** Shared setup for the login and register page tests. */
import type { InternalAxiosRequestConfig } from 'axios'
import { Route, Routes } from 'react-router'
import { LoginPage } from './login-page'
import { RegisterPage } from './register-page'
import { PublicOnlyRoute } from '../routes/public-only-route'
import { ProtectedRoute } from '../routes/protected-route'
import { resetSessionRestore } from '../lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { renderWithProviders, type RenderWithProvidersOptions } from '@/test/render-with-providers'

export interface AuthPageBackend {
  calls: InternalAxiosRequestConfig[]
  refreshCalls: () => number
}

/** Signed-out start (refresh 401) and an API that answers with `reply`. */
export function setupAuthBackend(reply: (config: InternalAxiosRequestConfig) => MockReply): AuthPageBackend {
  clearAccessToken()
  resetSessionRestore()
  let refreshes = 0
  refreshClient.defaults.adapter = mockAdapter(() => {
    refreshes++
    return { status: 401 }
  })
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply(config)
  })
  return { calls, refreshCalls: () => refreshes }
}

/** Renders the auth routes plus two protected pages. */
export function renderAuthRoutes(route: RenderWithProvidersOptions['route']) {
  return renderWithProviders(
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route path="/dashboard" element={<p>Dashboard page</p>} />
        <Route path="/budgets" element={<p>Budgets page</p>} />
      </Route>
    </Routes>,
    { route },
  )
}
