import { describe, expect, it } from 'vitest'
import {
  decimalToJson,
  JsonRawNumber,
  LosslessJsonError,
  parseLosslessJson,
  stringifyLosslessJson,
} from './lossless-json'
import { toDecimal } from '@/shared/money/decimal'

describe('parseLosslessJson', () => {
  it('matches JSON.parse for documents without fractional numbers', () => {
    const docs = [
      '{"id":1,"name":"Main","active":true,"tags":[],"note":null}',
      '[0,-1,9007199254740991,-9007199254740991]',
      ' { "a" : [ { "b" : [ 1 , 2 ] } ] } ',
      '"text"',
      'true',
      'null',
      '{"esc":"q\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9 \\ud83d\\ude00"}',
      '{"a":1,"a":2}',
    ]
    for (const doc of docs) {
      expect(parseLosslessJson(doc), doc).toEqual(JSON.parse(doc))
    }
  })

  it('keeps fractional and exponent literals as exact text', () => {
    expect(parseLosslessJson('{"balance":1250.40,"rate":1.5E+2,"tiny":1e-2}')).toEqual({
      balance: '1250.40',
      rate: '1.5E+2',
      tiny: '1e-2',
    })
  })

  it('keeps digits that JSON.parse would lose', () => {
    const parsed = parseLosslessJson('{"amount":90071992547409.93}')
    expect(parsed).toEqual({ amount: '90071992547409.93' })
  })

  it('refuses an integer outside the safe range', () => {
    expect(() => parseLosslessJson('9007199254740992')).toThrow(LosslessJsonError)
  })

  it('refuses malformed JSON', () => {
    const bad = [
      '',
      '   ',
      '{',
      '[1,]',
      '{"a":1,}',
      '{a:1}',
      "{'a':1}",
      '01',
      '1.',
      '.5',
      '+1',
      'NaN',
      'Infinity',
      '"open',
      '"tab\there"',
      '"bad \\x"',
      '"bad \\u12"',
      'tru',
      '{"a" 1}',
      '[1 2]',
      '{} {}',
    ]
    for (const doc of bad) {
      expect(() => parseLosslessJson(doc), JSON.stringify(doc)).toThrow(LosslessJsonError)
    }
  })

  it('keeps a "__proto__" key as plain data', () => {
    const parsed = parseLosslessJson('{"__proto__":{"polluted":true}}')
    expect(Object.getPrototypeOf(parsed)).toBe(Object.prototype)
    expect(Object.keys(parsed as object)).toEqual(['__proto__'])
    expect(({} as Record<string, unknown>).polluted).toBeUndefined()
  })

  it('limits nesting depth', () => {
    expect(() => parseLosslessJson('['.repeat(200) + ']'.repeat(200))).toThrow(LosslessJsonError)
    expect(parseLosslessJson('['.repeat(100) + ']'.repeat(100))).toBeInstanceOf(Array)
  })
})

describe('stringifyLosslessJson', () => {
  it('writes raw numbers verbatim', () => {
    const body = { accountId: 7, amount: decimalToJson(toDecimal('-9999999999999.99')), countryCode: 'US' }
    expect(stringifyLosslessJson(body)).toBe('{"accountId":7,"amount":-9999999999999.99,"countryCode":"US"}')
  })

  it('matches JSON.stringify for plain data', () => {
    const value = { s: 'a"b\\c\n', n: 3, b: false, z: null, list: [1, undefined, 'x'], skip: undefined }
    expect(stringifyLosslessJson(value)).toBe(JSON.stringify(value))
  })

  it('refuses fractional and non-finite numbers', () => {
    for (const bad of [0.1, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 53]) {
      expect(() => stringifyLosslessJson({ amount: bad }), String(bad)).toThrow(LosslessJsonError)
    }
  })

  it('refuses values JSON cannot hold', () => {
    for (const bad of [10n, () => 1, Symbol('s'), new Date(0), new Map(), undefined]) {
      expect(() => stringifyLosslessJson(bad), String(typeof bad)).toThrow(LosslessJsonError)
    }
  })

  it('refuses an invalid raw literal', () => {
    for (const bad of ['', '1.', '.5', '01', '1,0', 'NaN', '-']) {
      expect(() => new JsonRawNumber(bad), bad).toThrow(LosslessJsonError)
    }
  })
})
