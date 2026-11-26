import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useCreateTransaction, useDeleteTransaction, useRecategoriseTransaction, useUpdateTransaction } from './use-transaction-mutations'
import { apiClient } from '@/shared/api/client'
import { toDecimal } from '@/shared/money/decimal'
import { mockAdapter } from '@/test/mock-adapter'
import { json, transaction } from '@/test/fixtures'
import { createTestQueryClient } from '@/test/render-with-providers'

const EXPECTED = [['transactions'], ['accounts'], ['budget-progress'], ['analytics'], ['fraud-alerts']]

/** Runs one mutation and returns the query keys it invalidated. */
async function invalidatedBy<T>(useHook: () => { mutateAsync: (vars: T) => Promise<unknown> }, vars: T): Promise<unknown[]> {
  const queryClient = createTestQueryClient()
  const spy = vi.spyOn(queryClient, 'invalidateQueries')
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  const { result } = renderHook(useHook, { wrapper })
  await act(async () => {
    await result.current.mutateAsync(vars).catch(() => undefined)
  })
  return spy.mock.calls.map(([filters]) => filters?.queryKey)
}

beforeEach(() => {
  apiClient.defaults.adapter = mockAdapter((config) => (config.method === 'delete' ? { status: 204 } : { status: 200, body: json(transaction()) }))
})

describe('transaction mutations', () => {
  it('create refreshes every affected module, but not the forecast', async () => {
    const keys = await invalidatedBy(useCreateTransaction, { accountId: 1, amount: toDecimal('-1'), transactionDate: '2026-03-15', countryCode: 'US' })
    expect(keys).toEqual(EXPECTED)
    expect(keys).not.toContainEqual(['cashflow'])
  })

  it('update refreshes the same keys', async () => {
    const keys = await invalidatedBy(useUpdateTransaction, { id: 1, request: { amount: toDecimal('1'), transactionDate: '2026-03-15', category: 'OTHER' as const } })
    expect(keys).toEqual(EXPECTED)
  })

  it('recategorise refreshes the same keys', async () => {
    expect(await invalidatedBy(useRecategoriseTransaction, { id: 1, category: 'OTHER' as const })).toEqual(EXPECTED)
  })

  it('delete refreshes the same keys', async () => {
    expect(await invalidatedBy(useDeleteTransaction, 1)).toEqual(EXPECTED)
  })

  it('refreshes nothing when the call fails', async () => {
    apiClient.defaults.adapter = mockAdapter(() => ({ status: 409, body: '{"status":409}' }))
    expect(await invalidatedBy(useDeleteTransaction, 1)).toEqual([])
  })
})
