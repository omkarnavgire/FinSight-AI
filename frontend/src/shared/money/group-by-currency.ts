/**
 * Per-currency money handling (D-5).
 *
 * Amounts in different currencies are never added together: there are no exchange rates in
 * FinSight. Totals are kept per ISO 4217 code, summed exactly in `bigint` cents.
 */
import { type Decimal, fromCents, toCents } from './decimal'

export interface CurrencyTotal {
  currency: string
  total: Decimal
  /** How many items were summed. */
  count: number
}

/**
 * Sums amounts per currency.
 *
 * @param items any list (accounts, transactions, …)
 * @param currencyOf the item's ISO 4217 code
 * @param amountOf the item's exact amount
 * @returns one total per currency, sorted by code
 */
export function totalsByCurrency<T>(
  items: readonly T[],
  currencyOf: (item: T) => string,
  amountOf: (item: T) => Decimal,
): CurrencyTotal[] {
  const sums = new Map<string, { cents: bigint; count: number }>()
  for (const item of items) {
    const currency = currencyOf(item)
    const current = sums.get(currency) ?? { cents: 0n, count: 0 }
    sums.set(currency, { cents: current.cents + toCents(amountOf(item)), count: current.count + 1 })
  }
  return [...sums.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([currency, { cents, count }]) => ({ currency, total: fromCents(cents), count }))
}

/**
 * How many currencies a set of amounts spans. Backend aggregates (analytics, budget spent,
 * forecast) add every account together, so they are only meaningful in the `single` case.
 */
export type CurrencyMode =
  | { readonly kind: 'none' }
  | { readonly kind: 'single'; readonly currency: string }
  | { readonly kind: 'mixed'; readonly currencies: readonly string[] }

/**
 * Classifies a list of currency codes.
 *
 * @param currencies codes, duplicates allowed
 */
export function currencyMode(currencies: Iterable<string>): CurrencyMode {
  const unique = [...new Set(currencies)].sort()
  if (unique.length === 0) return { kind: 'none' }
  if (unique.length === 1 && unique[0] !== undefined) return { kind: 'single', currency: unique[0] }
  return { kind: 'mixed', currencies: unique }
}
