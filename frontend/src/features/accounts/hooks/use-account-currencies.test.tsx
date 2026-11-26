import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useAccountCurrencies } from './use-account-currencies'
import { apiClient } from '@/shared/api/client'
import { mockAdapter } from '@/test/mock-adapter'
import { account, json, page } from '@/test/fixtures'
import { createTestQueryClient } from '@/test/render-with-providers'

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={createTestQueryClient()}>{children}</QueryClientProvider>
}

function serve(rows: Parameters<typeof account>[0][]): void {
  apiClient.defaults.adapter = mockAdapter(() => ({ status: 200, body: json(page(rows.map((row) => account(row)), { size: 100 })) }))
}

describe('useAccountCurrencies', () => {
  it('has no mode while loading, so callers cannot show totals early', () => {
    serve([{ currency: 'USD' }])
    const { result } = renderHook(() => useAccountCurrencies(), { wrapper })
    expect(result.current.mode).toBeUndefined()
    expect(result.current.isPending).toBe(true)
  })

  it('reports mixed currencies across all accounts by default', async () => {
    serve([{ currency: 'USD' }, { currency: 'EUR', active: false }])
    const { result } = renderHook(() => useAccountCurrencies(), { wrapper })
    await waitFor(() => expect(result.current.mode).toEqual({ kind: 'mixed', currencies: ['EUR', 'USD'] }))
  })

  it('can count active accounts only', async () => {
    serve([{ currency: 'USD' }, { currency: 'EUR', active: false }])
    const { result } = renderHook(() => useAccountCurrencies(true), { wrapper })
    await waitFor(() => expect(result.current.mode).toEqual({ kind: 'single', currency: 'USD' }))
  })

  it('reports none without accounts', async () => {
    serve([])
    const { result } = renderHook(() => useAccountCurrencies(), { wrapper })
    await waitFor(() => expect(result.current.mode).toEqual({ kind: 'none' }))
  })

  it('exposes errors and keeps the mode undefined', async () => {
    apiClient.defaults.adapter = mockAdapter(() => ({ status: 500, body: '{"status":500}' }))
    const { result } = renderHook(() => useAccountCurrencies(), { wrapper })
    await waitFor(() => expect(result.current.error).not.toBeNull())
    expect(result.current.mode).toBeUndefined()
  })
})
