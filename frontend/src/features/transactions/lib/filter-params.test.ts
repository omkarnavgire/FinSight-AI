import { describe, expect, it } from 'vitest'
import { filterRangeErrors, hasFilters, isIsoDate, readFilterParams, writeFilterParams } from './filter-params'
import { toDecimal } from '@/shared/money/decimal'

const read = (query: string) => readFilterParams(new URLSearchParams(query))

describe('readFilterParams', () => {
  it('reads every valid filter and the page', () => {
    expect(read('accountId=7&from=2026-03-01&to=2026-03-31&category=DINING&minAmount=-100.5&maxAmount=0&page=2')).toEqual({
      filter: { accountId: 7, from: '2026-03-01', to: '2026-03-31', category: 'DINING', minAmount: '-100.50', maxAmount: '0.00' },
      page: 2,
    })
  })

  it('drops invalid values instead of sending them', () => {
    expect(read('accountId=0&from=2026-02-30&to=03/31/2026&category=GOLD&minAmount=1.005&maxAmount=1e3&page=-1')).toEqual({
      filter: {},
      page: 0,
    })
    expect(read('accountId=abc&page=1.5')).toEqual({ filter: {}, page: 0 })
  })

  it('keeps exact amounts beyond float precision', () => {
    expect(read('minAmount=-9999999999999.99').filter.minAmount).toBe('-9999999999999.99')
  })
})

describe('writeFilterParams', () => {
  it('round-trips and leaves page 0 out', () => {
    const state = { filter: { accountId: 3, category: 'INCOME' as const, maxAmount: toDecimal('-0.01') }, page: 0 }
    const params = writeFilterParams(state)
    expect(params.toString()).toBe('accountId=3&category=INCOME&maxAmount=-0.01')
    expect(readFilterParams(params)).toEqual(state)
  })

  it('writes the page when above 0', () => {
    expect(writeFilterParams({ filter: {}, page: 4 }).toString()).toBe('page=4')
  })
})

describe('filter rules', () => {
  it('matches the backend range checks', () => {
    expect(filterRangeErrors({ from: '2026-03-02', to: '2026-03-01' })).toEqual({ to: 'The end date must not be before the start date.' })
    expect(filterRangeErrors({ minAmount: toDecimal('-1'), maxAmount: toDecimal('-2') })).toEqual({
      maxAmount: 'The maximum must not be below the minimum.',
    })
    expect(filterRangeErrors({ from: '2026-03-01', to: '2026-03-01', minAmount: toDecimal('5'), maxAmount: toDecimal('5') })).toEqual({})
  })

  it('knows when filters are set', () => {
    expect(hasFilters({})).toBe(false)
    expect(hasFilters({ category: 'DINING' })).toBe(true)
  })

  it('checks real calendar dates', () => {
    expect(isIsoDate('2024-02-29')).toBe(true)
    expect(isIsoDate('2026-02-29')).toBe(false)
    expect(isIsoDate('2026-13-01')).toBe(false)
  })
})
