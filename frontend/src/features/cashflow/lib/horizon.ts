import type { ForecastHorizon } from '@/shared/types/api'

export const DEFAULT_HORIZON: ForecastHorizon = 30

/** Reads `?horizon=30|60|90`; anything else means 30. */
export function readHorizonParam(params: URLSearchParams): ForecastHorizon {
  const value = params.get('horizon')
  if (value === '60') return 60
  if (value === '90') return 90
  return DEFAULT_HORIZON
}

/** Message for a refused recalculation (429), from `Retry-After` seconds. */
export function refreshLimitMessage(seconds: number | null): string {
  if (seconds === null) return 'You can recalculate once per hour. Please try again later.'
  const minutes = Math.max(1, Math.ceil(seconds / 60))
  return `You can recalculate once per hour. Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`
}
