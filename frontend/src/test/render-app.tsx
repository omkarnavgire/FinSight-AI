import { useLayoutEffect } from 'react'
import { render } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { type Location, MemoryRouter, useLocation, useRoutes } from 'react-router'
import type { InternalAxiosRequestConfig } from 'axios'
import { appRoutes } from '@/app/router'
import { AuthProvider } from '@/features/auth/context/auth-provider'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { type MockReply, mockAdapter, tokenBody } from './mock-adapter'
import { createTestQueryClient } from './render-with-providers'
import { userJwt } from './fake-jwt'

export interface AppBackend {
  apiCalls: InternalAxiosRequestConfig[]
  refreshCalls: () => number
}

/**
 * Fakes the backend for whole-app tests: `signedIn` decides the boot refresh result, and
 * `api` answers every other call. Only the HTTP layer is faked; auth runs for real.
 */
export function setupAppBackend(signedIn: boolean, api: (config: InternalAxiosRequestConfig) => MockReply): AppBackend {
  clearAccessToken()
  resetSessionRestore()
  let refreshes = 0
  refreshClient.defaults.adapter = mockAdapter(() => {
    refreshes++
    return signedIn ? { status: 200, body: tokenBody(userJwt(7, 'demo@finsight.dev')) } : { status: 401 }
  })
  const apiCalls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter((config) => {
    apiCalls.push(config)
    return api(config)
  })
  return { apiCalls, refreshCalls: () => refreshes }
}

/** Renders the real route table (`appRoutes`) at `path`. */
export function renderApp(path: string) {
  const tracker: LocationTracker = { current: null }
  const router = {
    /** Same shape tests used with the data router: `router.state.location.pathname`. */
    get state(): { location: Location } {
      if (tracker.current === null) throw new Error('The router has not rendered yet')
      return { location: tracker.current }
    },
  }
  const queryClient = createTestQueryClient()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <LocationRecorder tracker={tracker} />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { router, queryClient, ...view }
}

/**
 * The production route objects rendered with `useRoutes` inside a `MemoryRouter`.
 *
 * Why not `createMemoryRouter`: the data router builds a fetch `Request` for every navigation.
 * Under jsdom, Node's `Request` (undici) rejects jsdom's `AbortSignal`, so navigations never
 * finish. `useRoutes` matches the same `RouteObject[]` without creating a `Request`.
 * Not covered here: `errorElement` boundaries, loaders, actions and other data-router APIs
 * (the app uses none of them besides `errorElement`).
 */
function AppRoutes() {
  return useRoutes(appRoutes)
}

interface LocationTracker {
  current: Location | null
}

/** Keeps the latest location for assertions. */
function LocationRecorder({ tracker }: { tracker: LocationTracker }) {
  const location = useLocation()
  useLayoutEffect(() => {
    tracker.current = location
  }, [tracker, location])
  return null
}
