import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import {
  createAccount,
  deactivateAccount,
  listAccounts,
  listAllAccounts,
  MAX_ACCOUNT_PAGES,
  readAccount,
  TooManyAccountsError,
} from './accounts-api'
import { apiClient } from '@/shared/api/client'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { toDecimal } from '@/shared/money/decimal'
import { clearAccessToken } from '@/shared/api/auth-token'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, json, page } from '@/test/fixtures'

let calls: InternalAxiosRequestConfig[]

function backend(reply: (config: InternalAxiosRequestConfig) => MockReply): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply(config)
  })
}

beforeEach(() => {
  clearAccessToken()
  calls = []
})

describe('readAccount', () => {
  it('maps every field and keeps money exact', () => {
    const wire = parseLosslessJson(json(account({ id: 3, type: 'CREDIT', openingBalance: '-250.00', balance: '90071992547409.93', currency: 'EUR' })))
    expect(readAccount(wire)).toEqual({
      id: 3,
      name: 'Main checking',
      type: 'CREDIT',
      openingBalance: '-250.00',
      balance: '90071992547409.93',
      currency: 'EUR',
      active: true,
      createdAt: '2026-03-01T09:00:00',
    })
  })

  it('refuses an unknown account type', () => {
    const wire = parseLosslessJson(json(account()).replace('"CHECKING"', '"GOLD"'))
    expect(() => readAccount(wire)).toThrow(WireFormatError)
  })
})

describe('accounts API', () => {
  it('lists one page with page and size', async () => {
    backend(() => ({ status: 200, body: json(page([account()], { size: 20 })) }))
    const result = await listAccounts(2, 20)
    expect(calls[0]?.url).toBe('/accounts')
    expect(calls[0]?.params).toEqual({ page: 2, size: 20 })
    expect(result.content[0]?.balance).toBe('1000.00')
  })

  it('reads every page at the maximum size until the last', async () => {
    backend((config) => {
      const { page: current } = config.params as { page: number }
      const items = [account({ id: current * 100 + 1 }), account({ id: current * 100 + 2 })]
      return { status: 200, body: json(page(items, { page: current, size: 100, totalElements: 250 })) }
    })
    const all = await listAllAccounts()
    expect(calls.map((c) => c.params)).toEqual([
      { page: 0, size: 100 },
      { page: 1, size: 100 },
      { page: 2, size: 100 },
    ])
    expect(all.map((a) => a.id)).toEqual([1, 2, 101, 102, 201, 202])
  })

  it('refuses to return a partial list', async () => {
    backend((config) => {
      const { page: current } = config.params as { page: number }
      return { status: 200, body: json(page([account()], { page: current, size: 100, totalElements: 1_000_000 })) }
    })
    await expect(listAllAccounts()).rejects.toBeInstanceOf(TooManyAccountsError)
    expect(calls).toHaveLength(MAX_ACCOUNT_PAGES)
  })

  it('sends the opening balance exactly and the currency when given', async () => {
    backend(() => ({ status: 201, body: json(account({ type: 'CREDIT', openingBalance: '-9999999999999.99', currency: 'EUR' })) }))
    const created = await createAccount({ name: 'Card', type: 'CREDIT', openingBalance: toDecimal('-9999999999999.99'), currency: 'EUR' })
    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.data).toBe('{"name":"Card","type":"CREDIT","openingBalance":-9999999999999.99,"currency":"EUR"}')
    expect(created.openingBalance).toBe('-9999999999999.99')
  })

  it('leaves the currency out when blank, so the backend default applies', async () => {
    backend(() => ({ status: 201, body: json(account()) }))
    await createAccount({ name: 'Main', type: 'CHECKING', openingBalance: toDecimal('0.10') })
    expect(calls[0]?.data).toBe('{"name":"Main","type":"CHECKING","openingBalance":0.10}')
  })

  it('deactivates with DELETE', async () => {
    backend(() => ({ status: 204 }))
    await deactivateAccount(42)
    expect(calls[0]?.method).toBe('delete')
    expect(calls[0]?.url).toBe('/accounts/42')
  })

  it('passes HTTP errors through', async () => {
    backend(() => ({ status: 404, body: '{"status":404,"detail":"Account not found"}' }))
    await expect(deactivateAccount(7)).rejects.toMatchObject({ response: { status: 404 } })
  })
})
