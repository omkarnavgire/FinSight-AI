import { describe, expect, it } from 'vitest'
import { emptyTransactionForm, formFromTransaction, validateCreateForm, validateEditForm } from './transaction-form'
import { toDecimal } from '@/shared/money/decimal'
import type { TransactionDto } from '@/shared/types/api'

const base = { ...emptyTransactionForm('2026-03-15'), accountId: '4', amount: '42.5', countryCode: ' us ' }

describe('validateCreateForm', () => {
  it('makes an expense negative, exactly', () => {
    expect(validateCreateForm({ ...base, amount: '9999999999999.99' })).toEqual({
      ok: true,
      request: { accountId: 4, amount: '-9999999999999.99', transactionDate: '2026-03-15', category: undefined, description: undefined, countryCode: 'US' },
    })
  })

  it('keeps income positive and sends a chosen category and description', () => {
    const result = validateCreateForm({ ...base, kind: 'INCOME', category: 'INCOME', description: ' Salary ' })
    expect(result.ok && result.request).toMatchObject({ amount: '42.50', category: 'INCOME', description: 'Salary' })
  })

  it('refuses zero, a negative sign, and missing account, date or country', () => {
    expect(validateCreateForm({ ...base, accountId: '', amount: '0', transactionDate: '', countryCode: 'U' })).toEqual({
      ok: false,
      errors: {
        amount: 'Enter an amount above zero.',
        transactionDate: 'Choose a date.',
        accountId: 'Choose an account.',
        countryCode: 'Use a 2-letter country code, like US or GB.',
      },
    })
    expect(validateCreateForm({ ...base, amount: '-5' })).toEqual({ ok: false, errors: { amount: 'Enter a positive amount.' } })
  })

  it('refuses a long description and too many decimals', () => {
    expect(validateCreateForm({ ...base, amount: '1.005', description: 'd'.repeat(256) })).toEqual({
      ok: false,
      errors: { amount: 'Use at most 2 digits after the point.', description: 'Use at most 255 characters.' },
    })
  })
})

describe('edit form', () => {
  const expense: TransactionDto = {
    id: 9,
    accountId: 4,
    amount: toDecimal('-0.29'),
    category: 'DINING',
    description: null,
    transactionDate: '2026-03-10',
    countryCode: 'GB',
    manual: false,
    createdAt: '2026-03-10T10:00:00',
  }

  it('shows an expense as a positive amount with Expense chosen', () => {
    expect(formFromTransaction(expense)).toMatchObject({ kind: 'EXPENSE', amount: '0.29', category: 'DINING', description: '' })
    expect(formFromTransaction({ ...expense, amount: toDecimal('12') })).toMatchObject({ kind: 'INCOME', amount: '12.00' })
  })

  it('round-trips the signed amount', () => {
    const result = validateEditForm(formFromTransaction(expense))
    expect(result).toEqual({ ok: true, request: { amount: '-0.29', transactionDate: '2026-03-10', category: 'DINING', description: undefined } })
  })

  it('needs a category on edit', () => {
    expect(validateEditForm({ ...formFromTransaction(expense), category: '' })).toEqual({ ok: false, errors: { category: 'Choose a category.' } })
  })
})
