import { type ComponentProps, type ReactElement, StrictMode } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { AuthProvider } from '@/features/auth/context/auth-provider'

type RouterEntry = NonNullable<ComponentProps<typeof MemoryRouter>['initialEntries']>[number]

export interface RenderWithProvidersOptions {
  /** Starting URL (string or `{ pathname, search, state }`). Default "/". */
  route?: RouterEntry
  /** Wrap in React StrictMode (double effects, like `npm run dev`). */
  strict?: boolean
  queryClient?: QueryClient
}

/** A query client for tests: no retries, no caching between tests. */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })
}

/** Renders `ui` inside the same providers the app uses, with an in-memory router. */
export function renderWithProviders(ui: ReactElement, options: RenderWithProvidersOptions = {}) {
  const queryClient = options.queryClient ?? createTestQueryClient()
  const tree = (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[options.route ?? '/']}>{ui}</MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
  return { queryClient, ...render(options.strict === true ? <StrictMode>{tree}</StrictMode> : tree) }
}
