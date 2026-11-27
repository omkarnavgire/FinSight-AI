import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { getForecast, readForecast, refreshForecasts } from './cashflow-api'
import { dailyPoints, fullForecast } from '@/test/forecast-fixtures'
import { apiClient } from '@/shared/api/client'
import { clearAccessToken } from '@/shared/api/auth-token'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { json } from '@/test/fixtures'

let calls: InternalAxiosRequestConfig[]

function backend(reply: MockReply): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply
  })
}

const parse = (value: unknown) => readForecast(parseLosslessJson(json(value)))

beforeEach(() => {
  clearAccessToken()
  calls = []
})

describe('readForecast', () => {
  it('maps every field with exact balances', () => {
    const result = parse(
      fullForecast(30, { startingBalance: '90071992547409.93', projectedEndBalance: '-0.29', days: dailyPoints(30, (i) => (i === 29 ? '-0.29' : '1.00')) }),
    )
    expect(result).toMatchObject({
      horizonDays: 30,
      asOf: '2026-03-15',
      startingBalance: '90071992547409.93',
      projectedEndBalance: '-0.29',
      lowConfidence: false,
      reason: null,
      generatedAt: '2026-03-15T02:00:00',
    })
    expect(result.days).toHaveLength(30)
    expect(result.days[29]).toEqual({ date: '2026-04-14', projectedBalance: '-0.29' })
  })

  it('reads the NO_HISTORY reason', () => {
    expect(parse(fullForecast(60, { lowConfidence: true, reason: 'NO_HISTORY' }))).toMatchObject({ lowConfidence: true, reason: 'NO_HISTORY' })
  })

  it('refuses unknown horizons and reasons, and a wrong number of days', () => {
    expect(() => parse({ ...fullForecast(30), horizonDays: 45 })).toThrow(WireFormatError)
    expect(() => parse({ ...fullForecast(30), reason: 'GUESS' })).toThrow(WireFormatError)
    expect(() => parse(fullForecast(30, { days: dailyPoints(29) }))).toThrow(WireFormatError)
  })
})

describe('cash-flow API', () => {
  it('always sends the horizon', async () => {
    backend({ status: 200, body: json(fullForecast(60)) })
    await getForecast(60)
    expect(calls[0]?.url).toBe('/cashflow/forecast')
    expect(calls[0]?.params).toEqual({ horizon: 60 })
  })

  it('recalculates all three horizons with POST', async () => {
    backend({ status: 200, body: json([fullForecast(90), fullForecast(30), fullForecast(60)]) })
    const result = await refreshForecasts()
    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/cashflow/refresh')
    expect(result.map((f) => f.horizonDays)).toEqual([90, 30, 60])
  })

  it('refuses a refresh answer without all three horizons', async () => {
    backend({ status: 200, body: json([fullForecast(30), fullForecast(30), fullForecast(60)]) })
    await expect(refreshForecasts()).rejects.toBeInstanceOf(WireFormatError)
    backend({ status: 200, body: json(fullForecast(30)) })
    await expect(refreshForecasts()).rejects.toBeInstanceOf(WireFormatError)
  })

  it('passes the 429 rate limit through with Retry-After', async () => {
    backend({ status: 429, body: '{"status":429}', headers: { 'Retry-After': '840' } })
    await expect(refreshForecasts()).rejects.toMatchObject({ response: { status: 429 } })
  })
})
