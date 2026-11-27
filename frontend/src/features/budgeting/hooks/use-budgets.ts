import { keepPreviousData, useQueries, useQuery } from '@tanstack/react-query'
import type { BudgetDto } from '@/shared/types/api'
import { getBudgetProgress, listBudgets } from '../api/budgets-api'
import { budgetKeys } from './budget-keys'

export const BUDGETS_PAGE_SIZE = 20

/** One page of the current month's budgets. */
export function useBudgets(page: number) {
  return useQuery({
    queryKey: budgetKeys.list(page, BUDGETS_PAGE_SIZE),
    queryFn: () => listBudgets(page, BUDGETS_PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
}

/** Progress for each budget on the page, for that budget's own period (one call each, R-12). */
export function useBudgetProgress(budgets: readonly BudgetDto[]) {
  return useQueries({
    queries: budgets.map((budget) => ({
      queryKey: budgetKeys.progress(budget.id, budget.periodYear, budget.periodMonth),
      queryFn: () => getBudgetProgress(budget.id, budget.periodYear, budget.periodMonth),
    })),
  })
}
