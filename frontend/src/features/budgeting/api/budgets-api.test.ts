import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { createBudget, deleteBudget, getBudgetProgress, listBudgets, readBudget, readBudgetProgress, updateBudget } from './budgets-api'
import { apiClient } from '@/shared/api/client'
import { clearAccessToken } from '@/shared/api/auth-token'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { toDecimal } from '@/shared/money/decimal'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { budget, budgetProgress, json, page } from '@/test/fixtures'

let calls: InternalAxiosRequestConfig[]

function backend(reply: MockReply): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply
  })
}

beforeEach(() => {
  clearAccessToken()
  calls = []
})

describe('budget mapping', () => {
  it('reads a budget with an exact limit', () => {
    expect(readBudget(parseLosslessJson(json(budget({ id: 4, monthlyLimit: '9999999999999.99' }))))).toEqual({
      id: 4,
      category: 'DINING',
      monthlyLimit: '9999999999999.99',
      periodYear: 2026,
      periodMonth: 3,
      createdAt: '2026-03-01T09:00:00',
    })
  })

  it('reads progress, including values over 100 % and both alert levels', () => {
    const wire = parseLosslessJson(json(budgetProgress({ spent: '320.00', remaining: '-20.00', percentUsed: '106.67', alertsFired: [80, 100] })))
    expect(readBudgetProgress(wire)).toEqual({
      budgetId: 1,
      category: 'DINING',
      year: 2026,
      month: 3,
      monthlyLimit: '300.00',
      spent: '320.00',
      remaining: '-20.00',
      percentUsed: '106.67',
      alertsFired: [80, 100],
    })
  })

  it('refuses an unknown alert level', () => {
    expect(() => readBudgetProgress(parseLosslessJson(json(budgetProgress({ alertsFired: [50] }))))).toThrow(WireFormatError)
  })
})

describe('budgets API', () => {
  it('lists the current month with page and size', async () => {
    backend({ status: 200, body: json(page([budget()])) })
    const result = await listBudgets(1, 20)
    expect(calls[0]?.url).toBe('/budgets')
    expect(calls[0]?.params).toEqual({ page: 1, size: 20 })
    expect(result.content[0]?.monthlyLimit).toBe('300.00')
  })

  it('asks for progress with year and month together', async () => {
    backend({ status: 200, body: json(budgetProgress()) })
    await getBudgetProgress(7, 2026, 3)
    expect(calls[0]?.url).toBe('/budgets/7/progress')
    expect(calls[0]?.params).toEqual({ year: 2026, month: 3 })
  })

  it('creates with an exact limit and no period (current month)', async () => {
    backend({ status: 201, body: json(budget()) })
    await createBudget({ category: 'DINING', monthlyLimit: toDecimal('0.10') })
    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.data).toBe('{"category":"DINING","monthlyLimit":0.10}')
  })

  it('updates only the limit with PUT', async () => {
    backend({ status: 200, body: json(budget({ id: 7 })) })
    await updateBudget(7, { monthlyLimit: toDecimal('1250.4') })
    expect(calls[0]?.method).toBe('put')
    expect(calls[0]?.url).toBe('/budgets/7')
    expect(calls[0]?.data).toBe('{"monthlyLimit":1250.40}')
  })

  it('deletes with DELETE', async () => {
    backend({ status: 204 })
    await deleteBudget(7)
    expect(calls[0]?.method).toBe('delete')
    expect(calls[0]?.url).toBe('/budgets/7')
  })

  it('passes a 409 duplicate through', async () => {
    backend({ status: 409, body: '{"status":409,"detail":"A budget for this category and period already exists"}' })
    await expect(createBudget({ category: 'DINING', monthlyLimit: toDecimal('1') })).rejects.toMatchObject({ response: { status: 409 } })
  })
})
