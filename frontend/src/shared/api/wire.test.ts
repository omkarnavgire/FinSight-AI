import { describe, expect, it } from 'vitest'
import {
  asRecord,
  wireBoolean,
  wireDecimal,
  wireEnum,
  wireInteger,
  WireFormatError,
  wireNullableString,
  wirePage,
  wireString,
} from './wire'
import { parseLosslessJson } from './lossless-json'

const record = (text: string) => asRecord(parseLosslessJson(text), 'Test')

describe('wire readers', () => {
  it('read well-formed fields', () => {
    const r = record('{"s":"x","n":null,"i":7,"b":true,"m":1250.40,"w":5000,"e":"SAVINGS"}')
    expect(wireString(r, 's')).toBe('x')
    expect(wireNullableString(r, 'n')).toBeNull()
    expect(wireInteger(r, 'i')).toBe(7)
    expect(wireBoolean(r, 'b')).toBe(true)
    expect(wireDecimal(r, 'm')).toBe('1250.40')
    expect(wireDecimal(r, 'w')).toBe('5000.00')
    expect(wireEnum(r, 'e', ['CHECKING', 'SAVINGS'] as const)).toBe('SAVINGS')
  })

  it('keep money exact past the float range', () => {
    expect(wireDecimal(record('{"m":90071992547409.93}'), 'm')).toBe('90071992547409.93')
  })

  it('refuse missing fields and wrong types', () => {
    const r = record('{"s":1,"i":1.5,"b":"yes","m":"abc","e":"GOLD"}')
    expect(() => wireString(r, 'missing')).toThrow(WireFormatError)
    expect(() => wireString(r, 's')).toThrow(WireFormatError)
    expect(() => wireInteger(r, 'i')).toThrow(WireFormatError)
    expect(() => wireBoolean(r, 'b')).toThrow(WireFormatError)
    expect(() => wireDecimal(r, 'm')).toThrow()
    expect(() => wireEnum(r, 'e', ['CHECKING'] as const)).toThrow(WireFormatError)
  })

  it('refuse non-objects', () => {
    for (const bad of ['[]', 'null', '"x"', '3']) {
      expect(() => record(bad), bad).toThrow(WireFormatError)
    }
  })

  it('read a page and each item', () => {
    const body = parseLosslessJson('{"content":[1,2],"page":0,"size":20,"totalElements":2,"totalPages":1,"last":true}')
    expect(wirePage(body, (item) => Number(item) * 10)).toEqual({
      content: [10, 20],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      last: true,
    })
    expect(() => wirePage(parseLosslessJson('{"content":{}}'), (item) => item)).toThrow(WireFormatError)
  })
})
