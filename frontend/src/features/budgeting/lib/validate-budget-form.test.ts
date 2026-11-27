import { describe, expect, it } from 'vitest'
import { BUDGET_CATEGORIES, checkMonthlyLimit, validateCreateBudget } from './validate-budget-form'

describe('budget form rules', () => {
  it('offers every spending category but not INCOME (S-1)', () => {
    expect(BUDGET_CATEGORIES).toEqual(['GROCERIES', 'TRANSPORT', 'UTILITIES', 'DINING', 'ENTERTAINMENT', 'OTHER'])
  })

  it('accepts a positive exact limit up to 13+2 digits', () => {
    expect(checkMonthlyLimit('9999999999999.99')).toEqual({ ok: true, value: '9999999999999.99' })
    expect(checkMonthlyLimit('0.01')).toEqual({ ok: true, value: '0.01' })
  })

  it('refuses zero, negative and over-precise limits', () => {
    expect(checkMonthlyLimit('0')).toEqual({ ok: false, error: 'Enter a limit above zero.' })
    expect(checkMonthlyLimit('-5')).toEqual({ ok: false, error: 'Enter a positive amount.' })
    expect(checkMonthlyLimit('1.005')).toEqual({ ok: false, error: 'Use at most 2 digits after the point.' })
    expect(checkMonthlyLimit('')).toEqual({ ok: false, error: 'Enter an amount.' })
  })

  it('builds a create request, and refuses INCOME even if sent', () => {
    expect(validateCreateBudget('DINING', '250.5')).toEqual({ ok: true, request: { category: 'DINING', monthlyLimit: '250.50' } })
    expect(validateCreateBudget('INCOME', '10')).toEqual({ ok: false, errors: { category: 'Choose a spending category.' } })
    expect(validateCreateBudget('', '')).toEqual({
      ok: false,
      errors: { category: 'Choose a spending category.', monthlyLimit: 'Enter an amount.' },
    })
  })
})
