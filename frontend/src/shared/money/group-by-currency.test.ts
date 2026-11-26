import { describe, expect, it } from 'vitest'
import { currencyMode, totalsByCurrency } from './group-by-currency'
import { type Decimal, toDecimal } from './decimal'

interface Item {
  currency: string
  amount: Decimal
}

const item = (currency: string, amount: string): Item => ({ currency, amount: toDecimal(amount) })
const totals = (items: Item[]) => totalsByCurrency(items, (i) => i.currency, (i) => i.amount)

describe('totalsByCurrency', () => {
  it('never adds different currencies together', () => {
    expect(totals([item('USD', '100.00'), item('EUR', '50.00'), item('USD', '0.10')])).toEqual([
      { currency: 'EUR', total: '50.00', count: 1 },
      { currency: 'USD', total: '100.10', count: 2 },
    ])
  })

  it('sums exactly, including values a float would round', () => {
    const many = Array.from({ length: 1000 }, () => item('USD', '0.10'))
    expect(totals(many)).toEqual([{ currency: 'USD', total: '100.00', count: 1000 }])
    expect(totals([item('EUR', '9999999999999.99'), item('EUR', '0.01')])[0]?.total).toBe('10000000000000.00')
  })

  it('keeps negative balances and zero totals', () => {
    expect(totals([item('USD', '-250.00'), item('USD', '250.00')])).toEqual([{ currency: 'USD', total: '0.00', count: 2 }])
  })

  it('returns nothing for no items', () => {
    expect(totals([])).toEqual([])
  })
})

describe('currencyMode', () => {
  it('classifies none, single and mixed', () => {
    expect(currencyMode([])).toEqual({ kind: 'none' })
    expect(currencyMode(['USD', 'USD'])).toEqual({ kind: 'single', currency: 'USD' })
    expect(currencyMode(['USD', 'EUR', 'USD', 'GBP'])).toEqual({ kind: 'mixed', currencies: ['EUR', 'GBP', 'USD'] })
  })
})
