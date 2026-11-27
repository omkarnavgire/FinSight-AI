/**
 * Analytics endpoints (`/api/v1/analytics`, T-71). "Spending" is debits as positive amounts, summed
 * by the backend over every account in any currency. Responses are checked with the wire readers;
 * money stays exact.
 */
import { apiClient } from '@/shared/api/client'
import { asRecord, WireFormatError, wireArray, wireDecimal, wireEnum, wireInteger, wireString } from '@/shared/api/wire'
import { CATEGORIES } from '@/shared/domain/categories'
import type { CategoryAmount, CategoryBreakdownDto, MonthAmount, MonthlyTrendDto } from '@/shared/types/api'

/** Most items in a top-categories answer (`AnalyticsService.TOP_LIMIT`). */
export const TOP_LIMIT = 5

export interface Period {
  year: number
  month: number
}

function readCategoryAmount(value: unknown): CategoryAmount {
  const record = asRecord(value, 'CategoryAmount')
  return {
    category: wireEnum(record, 'category', CATEGORIES),
    amount: wireDecimal(record, 'amount'),
    percent: wireDecimal(record, 'percent'),
  }
}

/** Reads a `CategoryBreakdownDto` (also used for top categories). */
export function readCategoryBreakdown(value: unknown): CategoryBreakdownDto {
  const record = asRecord(value, 'CategoryBreakdown')
  return {
    from: wireString(record, 'from'),
    to: wireString(record, 'to'),
    total: wireDecimal(record, 'total'),
    items: wireArray(record, 'items', readCategoryAmount),
  }
}

function readMonthAmount(value: unknown): MonthAmount {
  const record = asRecord(value, 'MonthAmount')
  return { year: wireInteger(record, 'year'), month: wireInteger(record, 'month'), spent: wireDecimal(record, 'spent') }
}

/** Reads a `MonthlyTrendDto`; months must be oldest first, as the backend promises. */
export function readMonthlyTrend(value: unknown): MonthlyTrendDto {
  const months = wireArray(asRecord(value, 'MonthlyTrend'), 'months', readMonthAmount)
  for (let i = 1; i < months.length; i++) {
    const before = months[i - 1]
    const current = months[i]
    if (before === undefined || current === undefined) continue
    if (before.year * 12 + before.month >= current.year * 12 + current.month) {
      throw new WireFormatError('Monthly trend is not oldest first')
    }
  }
  return { months }
}

/**
 * `GET /analytics/category-breakdown`. Year and month are sent together, or both left out for the
 * server's current month.
 */
export async function getCategoryBreakdown(period?: Period): Promise<CategoryBreakdownDto> {
  const params = period === undefined ? undefined : { year: period.year, month: period.month }
  const response = await apiClient.get<unknown>('/analytics/category-breakdown', { params })
  return readCategoryBreakdown(response.data)
}

/** `GET /analytics/monthly-trend`: this month and the five before it. */
export async function getMonthlyTrend(): Promise<MonthlyTrendDto> {
  const response = await apiClient.get<unknown>('/analytics/monthly-trend')
  return readMonthlyTrend(response.data)
}

/** `GET /analytics/top-categories?end`: the top five over the 30 days ending on `end` (default: today). */
export async function getTopCategories(end?: string): Promise<CategoryBreakdownDto> {
  const response = await apiClient.get<unknown>('/analytics/top-categories', { params: end === undefined ? undefined : { end } })
  const result = readCategoryBreakdown(response.data)
  if (result.items.length > TOP_LIMIT) throw new WireFormatError('Top categories has more than five items')
  return result
}
