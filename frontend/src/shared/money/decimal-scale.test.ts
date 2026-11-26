import { describe, expect, it } from 'vitest'
import { barPercent, centsRange, decimalExtremes, ratioInRange } from './decimal-scale'
import { type Decimal, toDecimal } from './decimal'

const d = (text: string): Decimal => toDecimal(text)

describe('centsRange', () => {
  it('finds min and max in cents', () => {
    expect(centsRange([d('5.00'), d('-2.50'), d('10.01')])).toEqual({ min: -250n, max: 1001n })
  })

  it('can include zero as a baseline', () => {
    expect(centsRange([d('5.00'), d('7.00')], true)).toEqual({ min: 0n, max: 700n })
  })

  it('returns null for an empty list', () => {
    expect(centsRange([])).toBeNull()
  })

  it('orders values beyond 2^53 exactly', () => {
    const range = centsRange([d('90071992547409.93'), d('90071992547409.92')])
    expect(range).toEqual({ min: 9007199254740992n, max: 9007199254740993n })
  })
})

describe('ratioInRange', () => {
  it('maps the range to 0–1 and clamps', () => {
    const range = { min: 0n, max: 1000n }
    expect(ratioInRange(d('0'), range)).toBe(0)
    expect(ratioInRange(d('2.50'), range)).toBe(0.25)
    expect(ratioInRange(d('10.00'), range)).toBe(1)
    expect(ratioInRange(d('-1'), range)).toBe(0)
    expect(ratioInRange(d('99'), range)).toBe(1)
  })

  it('puts every value in the middle of a flat range', () => {
    expect(ratioInRange(d('3'), { min: 300n, max: 300n })).toBe(0.5)
  })

  it('separates neighbours that floats cannot tell apart', () => {
    const range = { min: 9007199254740992n, max: 9007199254740994n }
    expect(ratioInRange(d('90071992547409.93'), range)).toBe(0.5)
  })
})

describe('barPercent', () => {
  it('scales against the largest value', () => {
    expect(barPercent(d('50'), d('200'))).toBe(25)
    expect(barPercent(d('200'), d('200'))).toBe(100)
  })

  it('draws nothing for zero, negative or an empty chart', () => {
    expect(barPercent(d('0'), d('200'))).toBe(0)
    expect(barPercent(d('-5'), d('200'))).toBe(0)
    expect(barPercent(d('5'), d('0'))).toBe(0)
  })
})

describe('decimalExtremes', () => {
  it('returns the original lowest and highest values', () => {
    expect(decimalExtremes([d('5.00'), d('-120.00'), d('45.50')])).toEqual({ min: '-120.00', max: '45.50' })
  })

  it('tells apart neighbours beyond 2^53', () => {
    expect(decimalExtremes([d('90071992547409.93'), d('90071992547409.92')])).toEqual({
      min: '90071992547409.92',
      max: '90071992547409.93',
    })
  })

  it('handles one value and none', () => {
    expect(decimalExtremes([d('5')])).toEqual({ min: '5.00', max: '5.00' })
    expect(decimalExtremes([])).toBeNull()
  })
})
