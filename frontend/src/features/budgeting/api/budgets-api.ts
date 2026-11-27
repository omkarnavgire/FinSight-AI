/**
 * Budgets endpoints (`/api/v1/budgets`, T-70). Money is read with `wireDecimal` and sent with
 * `decimalToJson`. Budgets are created for the current month only (S-2): the period is left out
 * so the backend's own month applies.
 */
import { apiClient } from '@/shared/api/client'
import { decimalToJson } from '@/shared/api/lossless-json'
import { asRecord, WireFormatError, wireArray, wireDecimal, wireEnum, wireInteger, wirePage, wireString } from '@/shared/api/wire'
import { CATEGORIES } from '@/shared/domain/categories'
import type { BudgetDto, BudgetProgressDto, Category, PageResponse, UpdateBudgetRequest } from '@/shared/types/api'
import type { Decimal } from '@/shared/money/decimal'

/** Reads one `BudgetDto`. */
export function readBudget(value: unknown): BudgetDto {
  const record = asRecord(value, 'Budget')
  return {
    id: wireInteger(record, 'id'),
    category: wireEnum(record, 'category', CATEGORIES),
    monthlyLimit: wireDecimal(record, 'monthlyLimit'),
    periodYear: wireInteger(record, 'periodYear'),
    periodMonth: wireInteger(record, 'periodMonth'),
    createdAt: wireString(record, 'createdAt'),
  }
}

function readAlertLevel(value: unknown): number {
  if (value !== 80 && value !== 100) throw new WireFormatError('Unknown budget alert level')
  return value
}

/** Reads one `BudgetProgressDto`; `alertsFired` may only hold 80 and/or 100. */
export function readBudgetProgress(value: unknown): BudgetProgressDto {
  const record = asRecord(value, 'BudgetProgress')
  return {
    budgetId: wireInteger(record, 'budgetId'),
    category: wireEnum(record, 'category', CATEGORIES),
    year: wireInteger(record, 'year'),
    month: wireInteger(record, 'month'),
    monthlyLimit: wireDecimal(record, 'monthlyLimit'),
    spent: wireDecimal(record, 'spent'),
    remaining: wireDecimal(record, 'remaining'),
    percentUsed: wireDecimal(record, 'percentUsed'),
    alertsFired: wireArray(record, 'alertsFired', readAlertLevel),
  }
}

function budgetUrl(id: number): string {
  return `/budgets/${encodeURIComponent(String(id))}`
}

/** `GET /budgets?page&size`: the current month's budgets, by category. */
export async function listBudgets(page: number, size: number): Promise<PageResponse<BudgetDto>> {
  const response = await apiClient.get<unknown>('/budgets', { params: { page, size } })
  return wirePage(response.data, readBudget)
}

/** `GET /budgets/{id}/progress?year&month`: year and month are always sent together. */
export async function getBudgetProgress(id: number, year: number, month: number): Promise<BudgetProgressDto> {
  const response = await apiClient.get<unknown>(`${budgetUrl(id)}/progress`, { params: { year, month } })
  return readBudgetProgress(response.data)
}

/** `POST /budgets` (201) for the current month. 409 if the category already has a budget. */
export async function createBudget(request: { category: Category; monthlyLimit: Decimal }): Promise<BudgetDto> {
  const response = await apiClient.post<unknown>('/budgets', {
    category: request.category,
    monthlyLimit: decimalToJson(request.monthlyLimit),
  })
  return readBudget(response.data)
}

/** `PUT /budgets/{id}`: only the limit can change. */
export async function updateBudget(id: number, request: UpdateBudgetRequest): Promise<BudgetDto> {
  const response = await apiClient.put<unknown>(budgetUrl(id), { monthlyLimit: decimalToJson(request.monthlyLimit) })
  return readBudget(response.data)
}

/** `DELETE /budgets/{id}` (204). */
export async function deleteBudget(id: number): Promise<void> {
  await apiClient.delete(budgetUrl(id))
}
