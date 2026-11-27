import { QUERY_ROOTS } from '@/shared/api/query-roots'

/** React Query keys for budgets, on the shared module roots. */
export const budgetKeys = {
  list: (page: number, size: number) => [...QUERY_ROOTS.budgets, 'list', page, size] as const,
  progress: (id: number, year: number, month: number) => [...QUERY_ROOTS.budgetProgress, id, year, month] as const,
}

/** What a budget change can affect: the list and every progress query (the dashboard card reuses both). */
export const AFFECTED_BY_BUDGETS = [QUERY_ROOTS.budgets, QUERY_ROOTS.budgetProgress] as const
