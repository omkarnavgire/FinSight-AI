import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useCreateBudget, useDeleteBudget, useUpdateBudget } from './use-budget-mutations'
import { apiClient } from '@/shared/api/client'
import { toDecimal } from '@/shared/money/decimal'
import { mockAdapter } from '@/test/mock-adapter'
import { budget, json } from '@/test/fixtures'
import { createTestQueryClient } from '@/test/render-with-providers'

const EXPECTED = [['budgets'], ['budget-progress']]

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
  apiClient.defaults.adapter = mockAdapter((config) => (config.method === 'delete' ? { status: 204 } : { status: 200, body: json(budget()) }))
})

describe('budget mutations', () => {
  it('create refreshes budgets and all progress', async () => {
    expect(await invalidatedBy(useCreateBudget, { category: 'DINING' as const, monthlyLimit: toDecimal('1') })).toEqual(EXPECTED)
  })

  it('update refreshes the same keys', async () => {
    expect(await invalidatedBy(useUpdateBudget, { id: 1, request: { monthlyLimit: toDecimal('2') } })).toEqual(EXPECTED)
  })

  it('delete refreshes the same keys', async () => {
    expect(await invalidatedBy(useDeleteBudget, 1)).toEqual(EXPECTED)
  })

  it('refreshes nothing when the call fails', async () => {
    apiClient.defaults.adapter = mockAdapter(() => ({ status: 409, body: '{"status":409}' }))
    expect(await invalidatedBy(useCreateBudget, { category: 'DINING' as const, monthlyLimit: toDecimal('1') })).toEqual([])
  })
})
