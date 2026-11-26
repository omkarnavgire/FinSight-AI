import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import {
  createTransaction,
  deleteTransaction,
  filterParams,
  listTransactions,
  readTransaction,
  recategoriseTransaction,
  updateTransaction,
} from './transactions-api'
import { apiClient } from '@/shared/api/client'
import { clearAccessToken } from '@/shared/api/auth-token'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { toDecimal } from '@/shared/money/decimal'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { json, page, transaction } from '@/test/fixtures'

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

describe('readTransaction', () => {
  it('maps every field, keeps the signed amount exact and allows no description', () => {
    const wire = parseLosslessJson(json(transaction({ id: 5, amount: '-90071992547409.93', description: null, manual: true })))
    expect(readTransaction(wire)).toEqual({
      id: 5,
      accountId: 1,
      amount: '-90071992547409.93',
      category: 'GROCERIES',
      description: null,
      transactionDate: '2026-03-15',
      countryCode: 'US',
      manual: true,
      createdAt: '2026-03-15T12:00:00',
    })
  })

  it('refuses an unknown category', () => {
    expect(() => readTransaction(parseLosslessJson(json(transaction()).replace('"GROCERIES"', '"PETS"')))).toThrow(WireFormatError)
  })
})

describe('filterParams', () => {
  it('sends only set filters, with amounts as exact text', () => {
    expect(filterParams({}, 0, 20)).toEqual({ page: 0, size: 20 })
    expect(filterParams({ accountId: 2, from: '2026-03-01', to: '2026-03-31', category: 'DINING', minAmount: toDecimal('-100'), maxAmount: toDecimal('0.29') }, 1, 20)).toEqual({
      page: 1,
      size: 20,
      accountId: 2,
      from: '2026-03-01',
      to: '2026-03-31',
      category: 'DINING',
      minAmount: '-100.00',
      maxAmount: '0.29',
    })
  })
})

describe('transactions API', () => {
  it('lists with filter params', async () => {
    backend({ status: 200, body: json(page([transaction()])) })
    const result = await listTransactions({ category: 'DINING' }, 0, 20)
    expect(calls[0]?.url).toBe('/transactions')
    expect(calls[0]?.params).toEqual({ page: 0, size: 20, category: 'DINING' })
    expect(result.content[0]?.amount).toBe('-42.50')
  })

  it('creates with an exact signed amount and no category for automatic', async () => {
    backend({ status: 201, body: json(transaction()) })
    await createTransaction({ accountId: 3, amount: toDecimal('-0.29'), transactionDate: '2026-03-15', countryCode: 'GB' })
    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.data).toBe('{"accountId":3,"amount":-0.29,"transactionDate":"2026-03-15","countryCode":"GB"}')
  })

  it('updates with PUT and the full body', async () => {
    backend({ status: 200, body: json(transaction({ id: 8 })) })
    await updateTransaction(8, { amount: toDecimal('1250.4'), transactionDate: '2026-03-01', category: 'INCOME', description: 'Pay' })
    expect(calls[0]?.method).toBe('put')
    expect(calls[0]?.url).toBe('/transactions/8')
    expect(calls[0]?.data).toBe('{"amount":1250.40,"transactionDate":"2026-03-01","category":"INCOME","description":"Pay"}')
  })

  it('recategorises with PATCH', async () => {
    backend({ status: 200, body: json(transaction({ id: 8, category: 'DINING', manual: true })) })
    const result = await recategoriseTransaction(8, 'DINING')
    expect(calls[0]?.method).toBe('patch')
    expect(calls[0]?.url).toBe('/transactions/8/category')
    expect(calls[0]?.data).toBe('{"category":"DINING"}')
    expect(result.manual).toBe(true)
  })

  it('deletes with DELETE', async () => {
    backend({ status: 204 })
    await deleteTransaction(8)
    expect(calls[0]?.method).toBe('delete')
    expect(calls[0]?.url).toBe('/transactions/8')
  })

  it('passes 409 and 400 errors through', async () => {
    backend({ status: 409, body: '{"status":409,"detail":"Account is inactive"}' })
    await expect(createTransaction({ accountId: 3, amount: toDecimal('-1'), transactionDate: '2026-03-15', countryCode: 'US' })).rejects.toMatchObject({
      response: { status: 409 },
    })
  })
})
