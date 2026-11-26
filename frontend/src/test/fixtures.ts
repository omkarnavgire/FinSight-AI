/**
 * Backend-shaped JSON for tests (test code only; the app never uses these).
 *
 * Builders take money as exact decimal text and emit it as bare JSON number literals through
 * `stringifyLosslessJson`, exactly as Jackson writes `BigDecimal`. Shapes are typed against
 * `shared/types/api.ts`, so a DTO change breaks the tests at compile time.
 */
import { JsonRawNumber, stringifyLosslessJson } from '@/shared/api/lossless-json'
import type {
  AccountDto,
  BudgetDto,
  BudgetProgressDto,
  CategoryBreakdownDto,
  FraudAlertDto,
  ForecastDto,
  MonthlyTrendDto,
  PageResponse,
  TransactionDto,
} from '@/shared/types/api'

type Raw = JsonRawNumber
const raw = (literal: string): Raw => new JsonRawNumber(literal)

/** Serialises a fixture to response text. */
export function json(value: unknown): string {
  return stringifyLosslessJson(value)
}

/** Wraps items in a `PageResponse`. */
export function page<T>(content: T[], options: { page?: number; size?: number; totalElements?: number } = {}): PageResponse<T> {
  const size = options.size ?? 20
  const totalElements = options.totalElements ?? content.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const current = options.page ?? 0
  return { content, page: current, size, totalElements, totalPages, last: current >= totalPages - 1 }
}

export function account(o: Partial<AccountDto<string>> = {}): AccountDto<Raw> {
  return {
    id: o.id ?? 1,
    name: o.name ?? 'Main checking',
    type: o.type ?? 'CHECKING',
    openingBalance: raw(o.openingBalance ?? '1000.00'),
    balance: raw(o.balance ?? '1000.00'),
    currency: o.currency ?? 'USD',
    active: o.active ?? true,
    createdAt: o.createdAt ?? '2026-03-01T09:00:00',
  }
}

export function transaction(o: Partial<TransactionDto<string>> = {}): TransactionDto<Raw> {
  return {
    id: o.id ?? 1,
    accountId: o.accountId ?? 1,
    amount: raw(o.amount ?? '-42.50'),
    category: o.category ?? 'GROCERIES',
    description: o.description === undefined ? 'Market' : o.description,
    transactionDate: o.transactionDate ?? '2026-03-15',
    countryCode: o.countryCode ?? 'US',
    manual: o.manual ?? false,
    createdAt: o.createdAt ?? '2026-03-15T12:00:00',
  }
}

export function budget(o: Partial<BudgetDto<string>> = {}): BudgetDto<Raw> {
  return {
    id: o.id ?? 1,
    category: o.category ?? 'DINING',
    monthlyLimit: raw(o.monthlyLimit ?? '300.00'),
    periodYear: o.periodYear ?? 2026,
    periodMonth: o.periodMonth ?? 3,
    createdAt: o.createdAt ?? '2026-03-01T09:00:00',
  }
}

export function budgetProgress(o: Partial<BudgetProgressDto<string>> = {}): BudgetProgressDto<Raw> {
  return {
    budgetId: o.budgetId ?? 1,
    category: o.category ?? 'DINING',
    year: o.year ?? 2026,
    month: o.month ?? 3,
    monthlyLimit: raw(o.monthlyLimit ?? '300.00'),
    spent: raw(o.spent ?? '120.00'),
    remaining: raw(o.remaining ?? '180.00'),
    percentUsed: raw(o.percentUsed ?? '40.00'),
    alertsFired: o.alertsFired ?? [],
  }
}

export interface BreakdownItemInput {
  category: CategoryBreakdownDto['items'][number]['category']
  amount: string
  percent: string
}

export function breakdown(items: BreakdownItemInput[], total: string, from = '2026-03-01', to = '2026-03-31'): CategoryBreakdownDto<Raw> {
  return {
    from,
    to,
    total: raw(total),
    items: items.map((item) => ({ category: item.category, amount: raw(item.amount), percent: raw(item.percent) })),
  }
}

export function trend(months: { year: number; month: number; spent: string }[]): MonthlyTrendDto<Raw> {
  return { months: months.map((m) => ({ year: m.year, month: m.month, spent: raw(m.spent) })) }
}

export interface ForecastInput {
  horizonDays?: ForecastDto['horizonDays']
  startingBalance?: string
  projectedEndBalance?: string
  lowConfidence?: boolean
  reason?: ForecastDto['reason']
  days?: { date: string; projectedBalance: string }[]
}

export function forecast(o: ForecastInput = {}): ForecastDto<Raw> {
  const horizonDays = o.horizonDays ?? 30
  return {
    horizonDays,
    asOf: '2026-03-15',
    from: '2026-03-16',
    to: '2026-04-14',
    startingBalance: raw(o.startingBalance ?? '1000.00'),
    projectedEndBalance: raw(o.projectedEndBalance ?? '900.00'),
    lowConfidence: o.lowConfidence ?? false,
    reason: o.reason ?? null,
    generatedAt: '2026-03-15T02:00:00',
    days: (o.days ?? [{ date: '2026-03-16', projectedBalance: '990.00' }]).map((day) => ({
      date: day.date,
      projectedBalance: raw(day.projectedBalance),
    })),
  }
}

export function fraudAlert(o: Partial<FraudAlertDto> = {}): FraudAlertDto {
  return {
    id: o.id ?? 1,
    transactionId: o.transactionId ?? 10,
    rule: o.rule ?? 'ABNORMAL_AMOUNT',
    severity: o.severity ?? 'MEDIUM',
    description: o.description ?? 'Amount is more than 3 times your usual spending in this category',
    acknowledged: o.acknowledged ?? false,
    acknowledgedAt: o.acknowledgedAt ?? null,
    createdAt: o.createdAt ?? '2026-03-15T12:00:01',
  }
}
