/**
 * React Query client settings (T-66, plan section 6).
 *
 * - Queries retry once, and only for network errors or 5xx. A 4xx will not change on retry.
 * - Mutations never retry: login is rate-limited and every attempt counts, and a retried
 *   create could duplicate data.
 */
import { QueryClient } from '@tanstack/react-query'
import { toApiError } from '@/shared/api/api-error'

const STALE_TIME_MS = 30_000

/** Retry policy for queries: once, for transient failures only. */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) return false
  const { kind, status } = toApiError(error)
  return kind === 'network' || (status !== null && status >= 500)
}

/** Creates the app's query client. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: STALE_TIME_MS, retry: shouldRetryQuery },
      mutations: { retry: false },
    },
  })
}
