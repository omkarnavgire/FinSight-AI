import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { getCategoryBreakdown, getMonthlyTrend, getTopCategories, readCategoryBreakdown, readMonthlyTrend } from './analytics-api'
import { apiClient } from '@/shared/api/client'
import { clearAccessToken } from '@/shared/api/auth-token'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { breakdown, json, trend } from '@/test/fixtures'

let calls: InternalAxiosRequestConfig[]

function backend(reply: MockReply): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply
  })
}

const sixMonths = (spent: string[]) => trend(spent.map((value, i) => ({ year: i < 2 ? 2025 : 2026, month: i < 2 ? 11 + i : i - 1, spent: value })))

beforeEach(() => {
  clearAccessToken()
  calls = []
})

describe('analytics mapping', () => {
  it('reads a breakdown with exact amounts and percentages', () => {
    const wire = parseLosslessJson(json(breakdown([{ category: 'DINING', amount: '90071992547409.93', percent: '99.99' }], '90071992547409.94')))
    expect(readCategoryBreakdown(wire)).toEqual({
      from: '2026-03-01',
      to: '2026-03-31',
      total: '90071992547409.94',
      items: [{ category: 'DINING', amount: '90071992547409.93', percent: '99.99' }],
    })
  })

  it('accepts a trend that is oldest first, and refuses any other order', () => {
    const ordered = readMonthlyTrend(parseLosslessJson(json(sixMonths(['1', '2', '3', '4', '5', '0.29']))))
    expect(ordered.months.map((m) => `${m.year}-${m.month}`)).toEqual(['2025-11', '2025-12', '2026-1', '2026-2', '2026-3', '2026-4'])
    expect(ordered.months[5]?.spent).toBe('0.29')
    const reversed = json(trend([{ year: 2026, month: 3, spent: '1' }, { year: 2026, month: 2, spent: '1' }]))
    expect(() => readMonthlyTrend(parseLosslessJson(reversed))).toThrow(WireFormatError)
  })
})

describe('analytics API', () => {
  it('asks for the current month with no parameters', async () => {
    backend({ status: 200, body: json(breakdown([], '0.00')) })
    await getCategoryBreakdown()
    expect(calls[0]?.url).toBe('/analytics/category-breakdown')
    expect(calls[0]?.params).toBeUndefined()
  })

  it('sends year and month together', async () => {
    backend({ status: 200, body: json(breakdown([], '0.00')) })
    await getCategoryBreakdown({ year: 2025, month: 12 })
    expect(calls[0]?.params).toEqual({ year: 2025, month: 12 })
  })

  it('reads the six-month trend', async () => {
    backend({ status: 200, body: json(sixMonths(['1', '2', '3', '4', '5', '6'])) })
    const result = await getMonthlyTrend()
    expect(calls[0]?.url).toBe('/analytics/monthly-trend')
    expect(result.months).toHaveLength(6)
  })

  it('reads the top five, with an optional end date', async () => {
    const five = (['GROCERIES', 'TRANSPORT', 'UTILITIES', 'DINING', 'ENTERTAINMENT'] as const).map((category) => ({ category, amount: '1.00', percent: '20.00' }))
    backend({ status: 200, body: json(breakdown(five, '5.00', '2026-02-14', '2026-03-15')) })
    expect((await getTopCategories()).items).toHaveLength(5)
    expect(calls[0]?.url).toBe('/analytics/top-categories')
    expect(calls[0]?.params).toBeUndefined()
    await getTopCategories('2026-03-15')
    expect(calls[1]?.params).toEqual({ end: '2026-03-15' })
  })

  it('refuses more than five top categories', async () => {
    const six = (['GROCERIES', 'TRANSPORT', 'UTILITIES', 'DINING', 'ENTERTAINMENT', 'OTHER'] as const).map((category) => ({ category, amount: '1.00', percent: '10.00' }))
    backend({ status: 200, body: json(breakdown(six, '6.00')) })
    await expect(getTopCategories()).rejects.toBeInstanceOf(WireFormatError)
  })

  it('passes a 400 for a bad period through', async () => {
    backend({ status: 400, body: '{"status":400,"detail":"year and month must be supplied together"}' })
    await expect(getCategoryBreakdown({ year: 2026, month: 3 })).rejects.toMatchObject({ response: { status: 400 } })
  })
})
