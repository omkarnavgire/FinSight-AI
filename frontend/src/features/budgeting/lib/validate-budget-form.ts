/**
 * Budget form checks, mirroring `CreateBudgetRequest` / `UpdateBudgetRequest`: a category and a
 * positive monthly limit with at most 13+2 digits. Budgets track spending, so `INCOME` is not
 * offered (S-1): spent only counts debits and would always be zero.
 */
import { CATEGORIES } from '@/shared/domain/categories'
import { type Decimal, describeMoneyInputError, isZeroDecimal, parseMoneyInput } from '@/shared/money/decimal'
import type { Category } from '@/shared/types/api'

export const BUDGET_CATEGORIES: readonly Category[] = CATEGORIES.filter((category) => category !== 'INCOME')

/** Checks a monthly limit; returns the exact value or a message. */
export function checkMonthlyLimit(text: string): { ok: true; value: Decimal } | { ok: false; error: string } {
  const parsed = parseMoneyInput(text)
  if (!parsed.ok) return { ok: false, error: describeMoneyInputError(parsed.error) }
  if (isZeroDecimal(parsed.value)) return { ok: false, error: 'Enter a limit above zero.' }
  return { ok: true, value: parsed.value }
}

export interface BudgetFormErrors {
  category?: string
  monthlyLimit?: string
}

/** Checks the create form. */
export function validateCreateBudget(
  category: Category | '',
  limit: string,
): { ok: true; request: { category: Category; monthlyLimit: Decimal } } | { ok: false; errors: BudgetFormErrors } {
  const errors: BudgetFormErrors = {}
  const chosen = BUDGET_CATEGORIES.find((option) => option === category)
  if (chosen === undefined) errors.category = 'Choose a spending category.'
  const checked = checkMonthlyLimit(limit)
  if (!checked.ok) errors.monthlyLimit = checked.error
  if (chosen === undefined || !checked.ok) return { ok: false, errors }
  return { ok: true, request: { category: chosen, monthlyLimit: checked.value } }
}
