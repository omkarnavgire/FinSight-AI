/**
 * Cash-flow endpoints (`/api/v1/cashflow`, T-72). The forecast starts from the plain sum of all
 * active account balances, in any currency (no conversion). Responses are checked with the wire
 * readers; money stays exact.
 */
import { apiClient } from '@/shared/api/client'
import { asRecord, WireFormatError, wireArray, wireBoolean, wireDecimal, wireInteger, wireNullableString, wireString } from '@/shared/api/wire'
import type { ForecastDayDto, ForecastDto, ForecastHorizon, ForecastReason } from '@/shared/types/api'

export const HORIZONS: readonly ForecastHorizon[] = [30, 60, 90]

function readHorizon(value: number): ForecastHorizon {
  const match = HORIZONS.find((horizon) => horizon === value)
  if (match === undefined) throw new WireFormatError('Unknown forecast horizon')
  return match
}

function readReason(value: string | null): ForecastReason | null {
  if (value === null || value === 'NO_HISTORY') return value
  throw new WireFormatError('Unknown forecast reason')
}

function readDay(value: unknown): ForecastDayDto {
  const record = asRecord(value, 'ForecastDay')
  return { date: wireString(record, 'date'), projectedBalance: wireDecimal(record, 'projectedBalance') }
}

/** Reads a `ForecastDto`: a known horizon, one point per day, and only known reasons. */
export function readForecast(value: unknown): ForecastDto {
  const record = asRecord(value, 'Forecast')
  const horizonDays = readHorizon(wireInteger(record, 'horizonDays'))
  const days = wireArray(record, 'days', readDay)
  if (days.length !== horizonDays) throw new WireFormatError('Forecast does not have one point per day')
  return {
    horizonDays,
    asOf: wireString(record, 'asOf'),
    from: wireString(record, 'from'),
    to: wireString(record, 'to'),
    startingBalance: wireDecimal(record, 'startingBalance'),
    projectedEndBalance: wireDecimal(record, 'projectedEndBalance'),
    lowConfidence: wireBoolean(record, 'lowConfidence'),
    reason: readReason(wireNullableString(record, 'reason')),
    generatedAt: wireString(record, 'generatedAt'),
    days,
  }
}

/** `GET /cashflow/forecast?horizon`: the horizon is always sent explicitly. */
export async function getForecast(horizon: ForecastHorizon): Promise<ForecastDto> {
  const response = await apiClient.get<unknown>('/cashflow/forecast', { params: { horizon } })
  return readForecast(response.data)
}

/**
 * `POST /cashflow/refresh`: recalculates all three horizons (rate-limited to once per hour;
 * 429 with `Retry-After` beyond that).
 */
export async function refreshForecasts(): Promise<ForecastDto[]> {
  const response = await apiClient.post<unknown>('/cashflow/refresh')
  if (!Array.isArray(response.data)) throw new WireFormatError('Refresh did not return a list')
  const forecasts = response.data.map(readForecast)
  const horizons = forecasts.map((forecast) => forecast.horizonDays).sort((a, b) => a - b)
  if (horizons.join(',') !== HORIZONS.join(',')) throw new WireFormatError('Refresh did not return all three horizons')
  return forecasts
}
