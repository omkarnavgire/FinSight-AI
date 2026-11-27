import { type QueryClient, useMutation, useQueryClient } from '@tanstack/react-query'
import { QUERY_ROOTS } from '@/shared/api/query-roots'
import type { Category, UpdateTransactionRequest } from '@/shared/types/api'
import { createTransaction, deleteTransaction, recategoriseTransaction, updateTransaction } from '../api/transactions-api'

/**
 * Everything a transaction change can affect (plan section 3): lists, account balances, budget
 * spending, analytics and fraud alerts (the dashboard cards reuse these queries). The forecast is
 * left alone on purpose (S-5): it is recalculated nightly or with its Recalculate button.
 */
export const AFFECTED_BY_TRANSACTIONS = [
  QUERY_ROOTS.transactions,
  QUERY_ROOTS.accounts,
  QUERY_ROOTS.budgetProgress,
  QUERY_ROOTS.analytics,
  QUERY_ROOTS.fraudAlerts,
] as const

/** Refreshes every query a transaction change can affect. */
export async function invalidateAfterTransactionChange(queryClient: QueryClient): Promise<void> {
  await Promise.all(AFFECTED_BY_TRANSACTIONS.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}

export function useCreateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({ mutationFn: createTransaction, retry: false, onSuccess: () => invalidateAfterTransactionChange(queryClient) })
}

export function useUpdateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: UpdateTransactionRequest }) => updateTransaction(id, request),
    retry: false,
    onSuccess: () => invalidateAfterTransactionChange(queryClient),
  })
}

export function useRecategoriseTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, category }: { id: number; category: Category }) => recategoriseTransaction(id, category),
    retry: false,
    onSuccess: () => invalidateAfterTransactionChange(queryClient),
  })
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient()
  return useMutation({ mutationFn: deleteTransaction, retry: false, onSuccess: () => invalidateAfterTransactionChange(queryClient) })
}
