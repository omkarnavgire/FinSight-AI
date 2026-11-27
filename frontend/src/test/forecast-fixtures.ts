/** Test helper: a backend-shaped forecast with one point per day (test code only). */
import type { ForecastDto } from '@/shared/types/api'
import { forecast, type ForecastInput } from './fixtures'

/** `count` daily points from 2026-03-16, each balance given by `balanceOf(index)`. */
export function dailyPoints(count: number, balanceOf: (index: number) => string = () => '1000.00') {
  return Array.from({ length: count }, (_, index) => ({
    date: new Date(Date.UTC(2026, 2, 16 + index)).toISOString().slice(0, 10),
    projectedBalance: balanceOf(index),
  }))
}

/** A full forecast for `horizon` days. */
export function fullForecast(horizon: ForecastDto['horizonDays'], input: ForecastInput = {}) {
  return forecast({ horizonDays: horizon, days: dailyPoints(horizon), ...input })
}
