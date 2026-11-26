import { describe, expect, it } from 'vitest'
import {
  addDecimal,
  compareDecimal,
  type Decimal,
  decimalFromWire,
  DecimalFormatError,
  DecimalPrecisionError,
  describeMoneyInputError,
  fromCents,
  isNegativeDecimal,
  isZeroDecimal,
  negateDecimal,
  parseMoneyInput,
  subtractDecimal,
  sumDecimals,
  toCents,
  toDecimal,
} from './decimal'

const d = (text: string): Decimal => toDecimal(text)

describe('toCents / fromCents', () => {
  it('reads API literals exactly, including scale and exponent forms', () => {
    expect(toCents('12')).toBe(1200n)
    expect(toCents('-12.5')).toBe(-1250n)
    expect(toCents('12.340')).toBe(1234n)
    expect(toCents('1.5E+2')).toBe(15000n)
    expect(toCents('1e-2')).toBe(1n)
    expect(toCents('0.00')).toBe(0n)
  })

  it('reads values a float cannot hold exactly', () => {
    expect(toCents('0.29')).toBe(29n)
    expect(toCents('4.35')).toBe(435n) // 4.35 * 100 === 434.99999999999994 as a float
    expect(toCents('90071992547409.93')).toBe(9007199254740993n) // above Number.MAX_SAFE_INTEGER cents
  })

  it('refuses digits below the cent instead of rounding', () => {
    expect(() => toCents('1.005')).toThrow(DecimalPrecisionError)
    expect(() => toCents('1E-3')).toThrow(DecimalPrecisionError)
  })

  it('refuses malformed literals', () => {
    for (const bad of ['', ' 1', '1,00', '+1', '.5', '1.', 'NaN', 'Infinity', '0x10', '1e', '--1']) {
      expect(() => toCents(bad), bad).toThrow(DecimalFormatError)
    }
  })

  it('refuses extreme exponents and lengths', () => {
    expect(() => toCents('1E+65')).toThrow(DecimalFormatError)
    expect(() => toCents('1'.repeat(65))).toThrow(DecimalFormatError)
  })

  it('formats cents canonically, with no negative zero', () => {
    expect(fromCents(0n)).toBe('0.00')
    expect(fromCents(-0n)).toBe('0.00')
    expect(fromCents(5n)).toBe('0.05')
    expect(fromCents(-5n)).toBe('-0.05')
    expect(fromCents(123456n)).toBe('1234.56')
    expect(toDecimal('-0.00')).toBe('0.00')
  })
})

describe('decimalFromWire', () => {
  it('normalises literal text', () => {
    expect(decimalFromWire('1250.4')).toBe('1250.40')
  })

  it('accepts safe integers that had no fraction', () => {
    expect(decimalFromWire(5000)).toBe('5000.00')
    expect(decimalFromWire(-3)).toBe('-3.00')
  })

  it('refuses a float, because precision may already be lost', () => {
    expect(() => decimalFromWire(0.29)).toThrow(DecimalPrecisionError)
  })

  it('refuses an unsafe integer', () => {
    expect(() => decimalFromWire(2 ** 53)).toThrow(DecimalPrecisionError)
  })
})

describe('arithmetic', () => {
  it('adds exactly where floats drift', () => {
    expect(0.1 + 0.2).not.toBe(0.3)
    expect(addDecimal(d('0.10'), d('0.20'))).toBe('0.30')
    expect(addDecimal(d('1.10'), d('2.20'))).toBe('3.30')
  })

  it('sums many small values exactly', () => {
    expect(sumDecimals(Array.from({ length: 10_000 }, () => d('0.10')))).toBe('1000.00')
    expect(sumDecimals(Array.from({ length: 10_000 }, () => d('0.01')))).toBe('100.00')
  })

  it('sums past the safe-integer range exactly', () => {
    const max = d('9999999999999.99')
    expect(sumDecimals(Array.from({ length: 1000 }, () => max))).toBe('9999999999999990.00')
    expect(addDecimal(max, d('0.01'))).toBe('10000000000000.00')
  })

  it('returns 0.00 for an empty sum', () => {
    expect(sumDecimals([])).toBe('0.00')
  })

  it('subtracts, negates and compares', () => {
    expect(subtractDecimal(d('100.00'), d('100.01'))).toBe('-0.01')
    expect(negateDecimal(d('12.50'))).toBe('-12.50')
    expect(negateDecimal(d('0.00'))).toBe('0.00')
    expect(compareDecimal(d('0.10'), d('0.09'))).toBe(1)
    expect(compareDecimal(d('-0.10'), d('0.09'))).toBe(-1)
    expect(compareDecimal(d('7.00'), d('7'))).toBe(0)
    expect(isNegativeDecimal(d('-0.01'))).toBe(true)
    expect(isZeroDecimal(d('-0'))).toBe(true)
  })
})

describe('parseMoneyInput', () => {
  it('accepts and normalises valid input', () => {
    expect(parseMoneyInput('1250.4')).toEqual({ ok: true, value: '1250.40' })
    expect(parseMoneyInput(' 12 ')).toEqual({ ok: true, value: '12.00' })
    expect(parseMoneyInput('0001.50')).toEqual({ ok: true, value: '1.50' })
    expect(parseMoneyInput('00000000000001.00')).toEqual({ ok: true, value: '1.00' })
    expect(parseMoneyInput('9999999999999.99')).toEqual({ ok: true, value: '9999999999999.99' })
  })

  it('needs a value', () => {
    expect(parseMoneyInput('')).toEqual({ ok: false, error: 'REQUIRED' })
    expect(parseMoneyInput('   ')).toEqual({ ok: false, error: 'REQUIRED' })
  })

  it('refuses a sign unless allowed', () => {
    expect(parseMoneyInput('-5')).toEqual({ ok: false, error: 'NEGATIVE_NOT_ALLOWED' })
    expect(parseMoneyInput('-250.00', { allowNegative: true })).toEqual({ ok: true, value: '-250.00' })
    expect(parseMoneyInput('-0', { allowNegative: true })).toEqual({ ok: true, value: '0.00' })
  })

  it('refuses more than 13 integer digits', () => {
    expect(parseMoneyInput('10000000000000')).toEqual({ ok: false, error: 'TOO_LARGE' })
    expect(parseMoneyInput('-10000000000000.00', { allowNegative: true })).toEqual({ ok: false, error: 'TOO_LARGE' })
  })

  it('refuses more than 2 decimals instead of rounding', () => {
    expect(parseMoneyInput('1.005')).toEqual({ ok: false, error: 'TOO_PRECISE' })
    expect(parseMoneyInput('8.675')).toEqual({ ok: false, error: 'TOO_PRECISE' })
    expect(parseMoneyInput('1.000')).toEqual({ ok: false, error: 'TOO_PRECISE' })
  })

  it('refuses other formats', () => {
    for (const bad of ['1,000.00', '+5', '1.', '.5', '1e3', 'abc', '--1', '1.2.3', '١٢', '12 34', '0x1F', 'Infinity']) {
      expect(parseMoneyInput(bad, { allowNegative: true }), bad).toEqual({ ok: false, error: 'FORMAT' })
    }
  })

  it('has a message for every error', () => {
    for (const error of ['REQUIRED', 'FORMAT', 'NEGATIVE_NOT_ALLOWED', 'TOO_LARGE', 'TOO_PRECISE'] as const) {
      expect(describeMoneyInputError(error)).not.toBe('')
    }
  })
})
