/**
 * Money and percentage display (D-4, D-5).
 *
 * Formatting works on the decimal string's digits (via `bigint` cents), never on a float, so
 * the shown value is exactly the stored value. Currency is shown as its ISO code ("USD"), and
 * left out on purpose for sums that mix currencies.
 */
import { type Decimal, toCents } from '@/shared/money/decimal'

const MINUS = '\u2212'

function groupDigits(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * Formats a money value: "1,234.56 USD", "−250.00 EUR", or "1,234.56" without a currency.
 *
 * @param value exact decimal
 * @param currency ISO 4217 code; omit for mixed-currency sums
 */
export function formatMoney(value: Decimal, currency?: string): string {
  const cents = toCents(value)
  const negative = cents < 0n
  const magnitude = negative ? -cents : cents
  const units = groupDigits((magnitude / 100n).toString())
  const fraction = (magnitude % 100n).toString().padStart(2, '0')
  const amount = `${negative ? MINUS : ''}${units}.${fraction}`
  return currency === undefined ? amount : `${amount} ${currency}`
}

/**
 * Formats a backend percentage (scale 2): "12.50%". Values above 999.99 show as "> 999%".
 *
 * @param value exact decimal percentage
 */
export function formatPercent(value: Decimal): string {
  if (toCents(value) > 99_999n) return '> 999%'
  return `${formatMoney(value)}%`
}

/**
 * A 0–100 number for widths and chart sizes only (never for money). Out-of-range values are clamped.
 *
 * @param value exact decimal percentage
 */
export function percentForWidth(value: Decimal): number {
  const cents = toCents(value)
  if (cents <= 0n) return 0
  if (cents >= 10_000n) return 100
  return Number(cents) / 100
}
