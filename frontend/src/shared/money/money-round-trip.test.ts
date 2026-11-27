/**
 * Money round trips over the full supported range: 13 integer digits + 2 decimals, both signs.
 *
 * Each value must survive every path without change:
 *   typed text → parseMoneyInput → Decimal
 *   Decimal → cents (bigint) → Decimal
 *   Decimal → request JSON text (stringifyLosslessJson) → parse → Decimal
 *   response JSON text (as the backend writes it) → parseLosslessJson → decimalFromWire → Decimal
 */
import { describe, expect, it } from 'vitest'
import { decimalFromWire, fromCents, parseMoneyInput, toCents, type Decimal } from './decimal'
import { decimalToJson, parseLosslessJson, stringifyLosslessJson } from '@/shared/api/lossless-json'

/** Fractions chosen to hit binary rounding trouble (0.01, 0.05, 0.29, 0.35 …) and both edges. */
const FRACTIONS = ['00', '01', '05', '09', '10', '15', '29', '30', '35', '49', '50', '51', '90', '99']

/** Integer parts per length 1–13: smallest, largest, a repeated 5, and a mixed pattern. */
function integerParts(length: number): string[] {
  const smallest = length === 1 ? '1' : `1${'0'.repeat(length - 1)}`
  const largest = '9'.repeat(length)
  const fives = '5'.repeat(length)
  const mixed = '1234567890123'.slice(0, length)
  return [...new Set([smallest, largest, fives, mixed])]
}

/** Deterministic pseudo-random values (LCG, fixed seed) across every length. */
function randomValues(count: number): string[] {
  let state = 20261120
  const next = (): number => {
    state = (state * 1664525 + 1013904223) >>> 0
    return state
  }
  const values: string[] = []
  for (let i = 0; i < count; i++) {
    const length = 1 + (next() % 13)
    let integer = String(1 + (next() % 9))
    for (let k = 1; k < length; k++) integer += String(next() % 10)
    const fraction = String(next() % 100).padStart(2, '0')
    const sign = next() % 2 === 0 ? '' : '-'
    values.push(`${sign}${integer}.${fraction}`)
  }
  return values
}

function allCases(): string[] {
  const cases = ['0.00', '0.01', '-0.01', '0.05', '-0.05', '0.10', '0.29', '-0.29', '0.99', '-0.99']
  for (let length = 1; length <= 13; length++) {
    for (const integer of integerParts(length)) {
      for (const fraction of FRACTIONS) {
        cases.push(`${integer}.${fraction}`, `-${integer}.${fraction}`)
      }
    }
  }
  cases.push('9999999999999.99', '-9999999999999.99', '9007199254740.99', '-9007199254740.99')
  return [...cases, ...randomValues(5000)]
}

const CASES = allCases()

function viaInput(text: string): Decimal {
  const result = parseMoneyInput(text, { allowNegative: true })
  if (!result.ok) throw new Error(`input refused: ${text} (${result.error})`)
  return result.value
}

function viaRequestJson(value: Decimal): Decimal {
  const text = stringifyLosslessJson({ amount: decimalToJson(value) })
  const parsed = parseLosslessJson(text) as { amount: string | number }
  return decimalFromWire(parsed.amount)
}

function viaResponseJson(literal: string): Decimal {
  const parsed = parseLosslessJson(`{"balance":${literal}}`) as { balance: string | number }
  return decimalFromWire(parsed.balance)
}

describe('money round trip across 13 + 2 digits', () => {
  it(`covers at least 6,000 values`, () => {
    expect(CASES.length).toBeGreaterThanOrEqual(6000)
  })

  it('typed text keeps its exact value', () => {
    for (const text of CASES) {
      expect(viaInput(text), text).toBe(text === '-0.00' ? '0.00' : text)
    }
  })

  it('bigint cents keep the exact value', () => {
    for (const text of CASES) {
      const value = viaInput(text)
      expect(fromCents(toCents(value)), text).toBe(value)
    }
  })

  it('request JSON keeps the exact value', () => {
    for (const text of CASES) {
      const value = viaInput(text)
      expect(viaRequestJson(value), text).toBe(value)
    }
  })

  it('response JSON keeps the exact value', () => {
    for (const text of CASES) {
      expect(viaResponseJson(text), text).toBe(viaInput(text))
    }
  })

  it('cents match the digits, with no float step', () => {
    for (const text of CASES) {
      const digits = text.replace('.', '').replace(/^(-?)0+(?=\d)/, '$1')
      expect(toCents(viaInput(text)).toString(), text).toBe(digits === '-000' ? '0' : digits)
    }
  })
})

describe('backend literal forms', () => {
  it('reads scale-0, scale-1 and exponent literals exactly', () => {
    expect(viaResponseJson('5000')).toBe('5000.00')
    expect(viaResponseJson('-250.5')).toBe('-250.50')
    expect(viaResponseJson('1.5E+2')).toBe('150.00')
    expect(viaResponseJson('12.340')).toBe('12.34')
  })

  it('reads values beyond the input range without loss', () => {
    expect(viaResponseJson('90071992547409.93')).toBe('90071992547409.93')
    expect(viaResponseJson('-123456789012345678.91')).toBe('-123456789012345678.91')
  })

  it('refuses sub-cent values instead of rounding', () => {
    expect(() => viaResponseJson('0.005')).toThrow()
    expect(() => viaResponseJson('1E-3')).toThrow()
  })
})
