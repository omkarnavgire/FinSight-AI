/**
 * Shared request/response types for all API calls.
 *
 * Rules (coding standards):
 * - No `any` — use `unknown` and narrow, or define a proper type.
 * - Request/response types live here, not in components.
 *
 * Every shape below was checked against the backend source (Phase 11 contract review).
 *
 * Money (Java `BigDecimal`) uses a type parameter `M`:
 * - `M = WireDecimal` for a response straight from `parseLosslessJson`;
 * - `M = RequestDecimal` for a request body handed to `stringifyLosslessJson`;
 * - `M = Decimal` (default) for values used by the app.
 * Feature API functions convert between them; money is never a float `number`.
 *
 * Dates (`LocalDate`) are ISO `YYYY-MM-DD` strings. Timestamps (`LocalDateTime`) are ISO
 * strings without a zone. IDs (`Long`) are safe integers.
 */
import type { JsonRawNumber } from '@/shared/api/lossless-json'
import type { Decimal } from '@/shared/money/decimal'

/** A BigDecimal as parsed losslessly: exact literal text, or a safe integer when it had no fraction. */
export type WireDecimal = string | number

/** A BigDecimal in a request body: written verbatim by `stringifyLosslessJson`. */
export type RequestDecimal = JsonRawNumber

// ---- Errors and paging ----

/** RFC 7807 problem detail from `GlobalExceptionHandler`. Only `status` is always present. */
export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  instance?: string
  /** Bean Validation failures as "field: message". */
  errors?: string[]
}

/** Generic paginated list response (`shared/pagination/PageResponse`). Size is capped at 100. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

// ---- Auth (/api/v1/auth) ----

/** Body of register, login and refresh responses. The refresh token is only ever a cookie. */
export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** Access-token lifetime in seconds. */
  expiresIn: number
}

/** `POST /auth/login`. */
export interface LoginRequest {
  email: string
  password: string
}

/** `POST /auth/register`. Email ≤ 255, password 8–72, full name ≤ 100 characters. */
export interface RegisterRequest {
  email: string
  password: string
  fullName: string
}

// ---- Enums ----

export type AccountType = 'CHECKING' | 'SAVINGS' | 'CREDIT'

export type Category = 'GROCERIES' | 'TRANSPORT' | 'UTILITIES' | 'DINING' | 'ENTERTAINMENT' | 'INCOME' | 'OTHER'

export type FraudRuleName = 'DUPLICATE_TRANSACTION' | 'ABNORMAL_AMOUNT' | 'UNSEEN_COUNTRY'

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH'

export type ForecastHorizon = 30 | 60 | 90

export type ForecastReason = 'NO_HISTORY'

// ---- Accounts (/api/v1/accounts) ----

export interface AccountDto<M = Decimal> {
  id: number
  name: string
  type: AccountType
  openingBalance: M
  balance: M
  /** ISO 4217 code, e.g. "USD". */
  currency: string
  active: boolean
  createdAt: string
}

/** `POST /accounts`. Name ≤ 100; currency `^[A-Z]{3}$`, omitted means "USD". */
export interface CreateAccountRequest<M = Decimal> {
  name: string
  type: AccountType
  openingBalance: M
  currency?: string
}

// ---- Transactions (/api/v1/transactions) ----

/** Amounts are signed: debits (spending) are negative, credits positive. */
export interface TransactionDto<M = Decimal> {
  id: number
  accountId: number
  amount: M
  category: Category
  description: string | null
  transactionDate: string
  /** ISO 3166-1 alpha-2. */
  countryCode: string
  /** True after a manual category change. */
  manual: boolean
  createdAt: string
}

/** `POST /transactions`. Category omitted means auto-categorise; description ≤ 255. */
export interface CreateTransactionRequest<M = Decimal> {
  accountId: number
  amount: M
  transactionDate: string
  category?: Category
  description?: string
  countryCode: string
}

/** `PUT /transactions/{id}`. Account and country cannot change. */
export interface UpdateTransactionRequest<M = Decimal> {
  amount: M
  transactionDate: string
  category: Category
  description?: string
}

/** `PATCH /transactions/{id}/category`. */
export interface CategoryOverrideRequest {
  category: Category
}

/** Query of `GET /transactions`. Amount bounds compare signed amounts; sent as decimal text. */
export interface TransactionFilter {
  accountId?: number
  from?: string
  to?: string
  category?: Category
  minAmount?: Decimal
  maxAmount?: Decimal
}

// ---- Budgets (/api/v1/budgets) ----

export interface BudgetDto<M = Decimal> {
  id: number
  category: Category
  monthlyLimit: M
  periodYear: number
  periodMonth: number
  createdAt: string
}

/** `POST /budgets`. Limit > 0; period omitted means the current month. Duplicate → 409. */
export interface CreateBudgetRequest<M = Decimal> {
  category: Category
  monthlyLimit: M
  periodYear?: number
  periodMonth?: number
}

/** `PUT /budgets/{id}`. */
export interface UpdateBudgetRequest<M = Decimal> {
  monthlyLimit: M
}

/** `GET /budgets/{id}/progress`. `percentUsed` may exceed 100; `alertsFired` holds 80 and/or 100. */
export interface BudgetProgressDto<M = Decimal> {
  budgetId: number
  category: Category
  year: number
  month: number
  monthlyLimit: M
  spent: M
  remaining: M
  percentUsed: M
  alertsFired: number[]
}

// ---- Analytics (/api/v1/analytics) — spending = debits as absolute values ----

export interface CategoryAmount<M = Decimal> {
  category: Category
  amount: M
  percent: M
}

export interface CategoryBreakdownDto<M = Decimal> {
  from: string
  to: string
  total: M
  items: CategoryAmount<M>[]
}

export interface MonthAmount<M = Decimal> {
  year: number
  month: number
  spent: M
}

/** Always six months, oldest first. */
export interface MonthlyTrendDto<M = Decimal> {
  months: MonthAmount<M>[]
}

// ---- Cash flow (/api/v1/cashflow) ----

export interface ForecastDayDto<M = Decimal> {
  date: string
  projectedBalance: M
}

/**
 * `startingBalance` is the plain sum of all active account balances, in any currency
 * (no conversion). The UI must say so when accounts use more than one currency.
 */
export interface ForecastDto<M = Decimal> {
  horizonDays: ForecastHorizon
  asOf: string
  from: string
  to: string
  startingBalance: M
  projectedEndBalance: M
  lowConfidence: boolean
  reason: ForecastReason | null
  generatedAt: string
  days: ForecastDayDto<M>[]
}

// ---- Fraud (/api/v1/fraud/alerts) — the list holds unacknowledged alerts only ----

export interface FraudAlertDto {
  id: number
  transactionId: number
  rule: FraudRuleName
  severity: Severity
  description: string
  acknowledged: boolean
  acknowledgedAt: string | null
  createdAt: string
}
