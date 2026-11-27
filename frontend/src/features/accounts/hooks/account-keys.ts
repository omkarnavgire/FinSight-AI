import { QUERY_ROOTS } from '@/shared/api/query-roots'

/** React Query keys for accounts, on the shared root. Invalidating `accountKeys.all` refreshes every accounts query. */
export const accountKeys = {
  all: QUERY_ROOTS.accounts,
  page: (page: number, size: number) => [...QUERY_ROOTS.accounts, 'page', page, size] as const,
  everything: [...QUERY_ROOTS.accounts, 'all'] as const,
}
