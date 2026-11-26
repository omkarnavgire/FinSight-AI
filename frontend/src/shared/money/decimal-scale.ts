/**
 * Maps exact money values to chart positions (D-3, D-4).
 *
 * Ranges and ratios are computed in `bigint` cents, so values beyond 2^53 still order and scale
 * correctly. Only the final 0–1 ratio becomes a `number`, and it is used for pixels and
 * percentages on screen, never as money.
 */
import { type Decimal, toCents } from './decimal'

/** Inclusive range in cents. */
export interface CentsRange {
  min: bigint
  max: bigint
}

const RATIO_STEPS = 1_000_000n

/**
 * The smallest and largest value, optionally widened to include zero (for bar baselines).
 *
 * @returns the range, or `null` for an empty list
 */
export function centsRange(values: readonly Decimal[], includeZero = false): CentsRange | null {
  if (values.length === 0) return null
  let min = includeZero ? 0n : toCents(values[0] as Decimal)
  let max = min
  for (const value of values) {
    const cents = toCents(value)
    if (cents < min) min = cents
    if (cents > max) max = cents
  }
  return { min, max }
}

/**
 * Where `value` sits in `range`, from 0 (min) to 1 (max). A flat range gives 0.5.
 * Values outside the range are clamped.
 */
export function ratioInRange(value: Decimal, range: CentsRange): number {
  const span = range.max - range.min
  if (span === 0n) return 0.5
  let offset = toCents(value) - range.min
  if (offset < 0n) offset = 0n
  if (offset > span) offset = span
  return Number((offset * RATIO_STEPS) / span) / Number(RATIO_STEPS)
}

/**
 * Bar length as a 0–100 percentage of the largest value. Zero and negative values give 0.
 *
 * @param value the bar's value
 * @param largest the largest value in the chart
 */
export function barPercent(value: Decimal, largest: Decimal): number {
  const top = toCents(largest)
  const cents = toCents(value)
  if (top <= 0n || cents <= 0n) return 0
  return ratioInRange(value, { min: 0n, max: top }) * 100
}

/**
 * The lowest and highest of `values`, compared in exact cents (for axis labels).
 *
 * @returns the original `Decimal`s, or `null` for an empty list
 */
export function decimalExtremes(values: readonly Decimal[]): { min: Decimal; max: Decimal } | null {
  const first = values[0]
  if (first === undefined) return null
  let min = first
  let max = first
  for (const value of values) {
    if (toCents(value) < toCents(min)) min = value
    if (toCents(value) > toCents(max)) max = value
  }
  return { min, max }
}
