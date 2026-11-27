import { type QueryClient, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UpdateBudgetRequest } from '@/shared/types/api'
import { createBudget, deleteBudget, updateBudget } from '../api/budgets-api'
import { AFFECTED_BY_BUDGETS } from './budget-keys'

/** Refreshes the budget list and all budget progress. */
export async function invalidateAfterBudgetChange(queryClient: QueryClient): Promise<void> {
  await Promise.all(AFFECTED_BY_BUDGETS.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}

export function useCreateBudget() {
  const queryClient = useQueryClient()
  return useMutation({ mutationFn: createBudget, retry: false, onSuccess: () => invalidateAfterBudgetChange(queryClient) })
}

export function useUpdateBudget() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: UpdateBudgetRequest }) => updateBudget(id, request),
    retry: false,
    onSuccess: () => invalidateAfterBudgetChange(queryClient),
  })
}

export function useDeleteBudget() {
  const queryClient = useQueryClient()
  return useMutation({ mutationFn: deleteBudget, retry: false, onSuccess: () => invalidateAfterBudgetChange(queryClient) })
}
