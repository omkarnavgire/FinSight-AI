import { type Decimal, toCents } from '@/shared/money/decimal'

export type ProgressLevel = 'ok' | 'near' | 'over'

/** Matches the backend budget alerts: 80 % and 100 %. Compared in exact cents. */
export function progressLevel(percent: Decimal): ProgressLevel {
  const cents = toCents(percent)
  if (cents >= 10_000n) return 'over'
  if (cents >= 8_000n) return 'near'
  return 'ok'
}
