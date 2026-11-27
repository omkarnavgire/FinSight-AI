import { describe, expect, it } from 'vitest'
import { validateAccountForm } from './validate-account-form'

const good = { name: ' Main ', type: 'CHECKING' as const, openingBalance: '1250.4', currency: ' eur ' }

describe('validateAccountForm', () => {
  it('builds an exact, trimmed request', () => {
    expect(validateAccountForm(good)).toEqual({
      ok: true,
      request: { name: 'Main', type: 'CHECKING', openingBalance: '1250.40', currency: 'EUR' },
    })
  })

  it('allows a negative balance and the 13+2 digit limit', () => {
    const result = validateAccountForm({ ...good, type: 'CREDIT', openingBalance: '-9999999999999.99' })
    expect(result.ok && result.request.openingBalance).toBe('-9999999999999.99')
  })

  it('leaves a blank currency out', () => {
    const result = validateAccountForm({ ...good, currency: '  ' })
    expect(result.ok && result.request.currency).toBeUndefined()
  })

  it('reports every problem at once', () => {
    expect(validateAccountForm({ name: '', type: '', openingBalance: '1.005', currency: 'US' })).toEqual({
      ok: false,
      errors: {
        name: 'Enter a name.',
        type: 'Choose an account type.',
        openingBalance: 'Use at most 2 digits after the point.',
        currency: 'Use a 3-letter code, like USD or EUR.',
      },
    })
  })

  it('refuses long names and oversized balances', () => {
    const result = validateAccountForm({ ...good, name: 'n'.repeat(101), openingBalance: '10000000000000' })
    expect(result).toEqual({
      ok: false,
      errors: { name: 'Use at most 100 characters.', openingBalance: 'Use at most 13 digits before the point.' },
    })
  })
})
