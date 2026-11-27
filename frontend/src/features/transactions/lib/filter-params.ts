/**
 * Transaction filters in the URL (D-8). The query string is untrusted input: every value is
 * checked, and anything invalid is dropped instead of being sent to the backend.
 */
import { compareDecimal, parseMoneyInput } from '@/shared/money/decimal'
import { CATEGORIES } from '@/shared/domain/categories'
import type { TransactionFilter } from '@/shared/types/api'

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/
const POSITIVE_ID = /^[1-9]\d{0,14}$/
const PAGE = /^\d{1,6}$/

/** True for a real calendar date in `YYYY-MM-DD` form. */
export function isIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value)
  if (!match) return false
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])]
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
}

export interface FilterState {
  filter: TransactionFilter
  page: number
}

/** Reads filters and page from the query string. */
export function readFilterParams(params: URLSearchParams): FilterState {
  const filter: TransactionFilter = {}
  const accountId = params.get('accountId')
  if (accountId !== null && POSITIVE_ID.test(accountId)) filter.accountId = Number(accountId)
  const from = params.get('from')
  if (from !== null && isIsoDate(from)) filter.from = from
  const to = params.get('to')
  if (to !== null && isIsoDate(to)) filter.to = to
  const category = CATEGORIES.find((option) => option === params.get('category'))
  if (category !== undefined) filter.category = category
  for (const key of ['minAmount', 'maxAmount'] as const) {
    const raw = params.get(key)
    if (raw === null) continue
    const parsed = parseMoneyInput(raw, { allowNegative: true })
    if (parsed.ok) filter[key] = parsed.value
  }
  const pageText = params.get('page')
  const page = pageText !== null && PAGE.test(pageText) ? Number(pageText) : 0
  return { filter, page }
}

/** Writes filters and page to a query string (page 0 is left out). */
export function writeFilterParams({ filter, page }: FilterState): URLSearchParams {
  const params = new URLSearchParams()
  if (filter.accountId !== undefined) params.set('accountId', String(filter.accountId))
  if (filter.from !== undefined) params.set('from', filter.from)
  if (filter.to !== undefined) params.set('to', filter.to)
  if (filter.category !== undefined) params.set('category', filter.category)
  if (filter.minAmount !== undefined) params.set('minAmount', filter.minAmount)
  if (filter.maxAmount !== undefined) params.set('maxAmount', filter.maxAmount)
  if (page > 0) params.set('page', String(page))
  return params
}

/** The same range rules as the backend: `from` ≤ `to`, `minAmount` ≤ `maxAmount`. */
export function filterRangeErrors(filter: TransactionFilter): { to?: string; maxAmount?: string } {
  const errors: { to?: string; maxAmount?: string } = {}
  if (filter.from !== undefined && filter.to !== undefined && filter.from > filter.to) {
    errors.to = 'The end date must not be before the start date.'
  }
  if (filter.minAmount !== undefined && filter.maxAmount !== undefined && compareDecimal(filter.minAmount, filter.maxAmount) > 0) {
    errors.maxAmount = 'The maximum must not be below the minimum.'
  }
  return errors
}

/** True when at least one filter is set. */
export function hasFilters(filter: TransactionFilter): boolean {
  return Object.values(filter).some((value) => value !== undefined)
}
