/**
 * Lossless JSON for API traffic (Phase 11, D-4).
 *
 * `JSON.parse` turns every number literal into a binary double, so a BigDecimal such as
 * 90071992547409.93 can arrive changed before any code sees it. This module reads and
 * writes JSON without that step:
 *
 * - {@link parseLosslessJson}: a strict RFC 8259 parser. A number literal with a fraction or
 *   exponent is returned as its exact source text (a `string`). A literal without one is
 *   returned as a `number` only when it is a safe integer; otherwise parsing fails.
 * - {@link stringifyLosslessJson}: writes {@link JsonRawNumber} values as bare number
 *   literals, so request money is sent from decimal text, never from a float. Plain
 *   fractional `number` values are refused.
 */
import type { Decimal } from '@/shared/money/decimal'

/** A parsed JSON value. Fractional and exponent number literals appear as exact `string` text. */
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }

const MAX_DEPTH = 128

/** Full JSON number grammar, anchored, for validating raw literals. */
const NUMBER_LITERAL = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/

/** Same grammar, sticky, for scanning inside a document. */
const NUMBER_TOKEN = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y

const HEX4 = /^[0-9a-fA-F]{4}$/

const SIMPLE_ESCAPES: Readonly<Record<string, string>> = {
  '"': '"',
  '\\': '\\',
  '/': '/',
  b: '\b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
}

/** Thrown for malformed JSON or for values that cannot be handled without precision loss. */
export class LosslessJsonError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'LosslessJsonError'
  }
}

/** A number literal that {@link stringifyLosslessJson} writes verbatim (e.g. "1250.40"). */
export class JsonRawNumber {
  readonly literal: string

  constructor(literal: string) {
    if (!NUMBER_LITERAL.test(literal)) {
      throw new LosslessJsonError('Not a JSON number literal')
    }
    this.literal = literal
  }
}

/**
 * Wraps an exact money value for a request body. A canonical {@link Decimal} always
 * matches the JSON number grammar, so no conversion happens.
 */
export function decimalToJson(value: Decimal): JsonRawNumber {
  return new JsonRawNumber(value)
}

/**
 * Parses JSON text without converting fractional numbers to floats.
 *
 * @param text complete JSON document
 * @returns the parsed value
 * @throws LosslessJsonError for malformed JSON, nesting deeper than 128, or an unsafe integer
 */
export function parseLosslessJson(text: string): JsonValue {
  let pos = 0

  function fail(reason: string): never {
    throw new LosslessJsonError(`${reason} at position ${pos}`)
  }

  function skipWhitespace(): void {
    while (pos < text.length) {
      const code = text.charCodeAt(pos)
      if (code !== 0x20 && code !== 0x09 && code !== 0x0a && code !== 0x0d) return
      pos++
    }
  }

  function parseValue(depth: number): JsonValue {
    if (depth > MAX_DEPTH) fail('Nesting is too deep')
    skipWhitespace()
    switch (text[pos]) {
      case '{':
        return parseObject(depth)
      case '[':
        return parseArray(depth)
      case '"':
        return parseString()
      case 't':
        return parseWord('true', true)
      case 'f':
        return parseWord('false', false)
      case 'n':
        return parseWord('null', null)
      default:
        return parseNumber()
    }
  }

  function parseWord<T extends boolean | null>(word: string, value: T): T {
    if (!text.startsWith(word, pos)) fail('Unexpected token')
    pos += word.length
    return value
  }

  function parseNumber(): number | string {
    if (pos >= text.length) fail('Unexpected end of input')
    NUMBER_TOKEN.lastIndex = pos
    const match = NUMBER_TOKEN.exec(text)
    if (!match) fail('Unexpected character')
    const literal = match[0]
    pos += literal.length
    if (/[.eE]/.test(literal)) {
      return literal
    }
    const value = Number(literal)
    if (!Number.isSafeInteger(value)) fail('Integer is outside the safe range')
    return value
  }

  function parseString(): string {
    pos++ // opening quote
    let out = ''
    for (;;) {
      if (pos >= text.length) fail('Unterminated string')
      const code = text.charCodeAt(pos)
      if (code === 0x22) {
        pos++
        return out
      }
      if (code < 0x20) fail('Control character in string')
      if (code !== 0x5c) {
        out += text[pos]
        pos++
        continue
      }
      const escape = text[pos + 1] ?? ''
      if (escape === 'u') {
        const hex = text.slice(pos + 2, pos + 6)
        if (!HEX4.test(hex)) fail('Invalid unicode escape')
        out += String.fromCharCode(Number.parseInt(hex, 16))
        pos += 6
        continue
      }
      const replacement = SIMPLE_ESCAPES[escape]
      if (replacement === undefined) fail('Invalid escape')
      out += replacement
      pos += 2
    }
  }

  function parseArray(depth: number): JsonValue[] {
    pos++ // [
    const items: JsonValue[] = []
    skipWhitespace()
    if (text[pos] === ']') {
      pos++
      return items
    }
    for (;;) {
      items.push(parseValue(depth + 1))
      skipWhitespace()
      if (text[pos] === ',') {
        pos++
        continue
      }
      if (text[pos] === ']') {
        pos++
        return items
      }
      fail("Expected ',' or ']'")
    }
  }

  function parseObject(depth: number): { [key: string]: JsonValue } {
    pos++ // {
    const result: { [key: string]: JsonValue } = {}
    skipWhitespace()
    if (text[pos] === '}') {
      pos++
      return result
    }
    for (;;) {
      skipWhitespace()
      if (text[pos] !== '"') fail('Expected a string key')
      const key = parseString()
      skipWhitespace()
      if (text[pos] !== ':') fail("Expected ':'")
      pos++
      const value = parseValue(depth + 1)
      // defineProperty keeps a "__proto__" key as plain data, as JSON.parse does.
      Object.defineProperty(result, key, { value, enumerable: true, writable: true, configurable: true })
      skipWhitespace()
      if (text[pos] === ',') {
        pos++
        continue
      }
      if (text[pos] === '}') {
        pos++
        return result
      }
      fail("Expected ',' or '}'")
    }
  }

  const value = parseValue(0)
  skipWhitespace()
  if (pos !== text.length) fail('Unexpected data after JSON')
  return value
}

/**
 * Serialises a request body. {@link JsonRawNumber} values become bare number literals.
 *
 * Follows `JSON.stringify` for plain data: `undefined` object properties are skipped and
 * `undefined` array items become `null`. Refuses fractional or non-finite `number` values
 * (money must use {@link JsonRawNumber}), bigint, functions, symbols, class instances
 * other than {@link JsonRawNumber}, and nesting deeper than 128.
 *
 * @param value plain data
 * @returns JSON text
 */
export function stringifyLosslessJson(value: unknown): string {
  return write(value, 0)
}

function write(value: unknown, depth: number): string {
  if (depth > MAX_DEPTH) throw new LosslessJsonError('Nesting is too deep')
  if (value instanceof JsonRawNumber) return value.literal
  switch (typeof value) {
    case 'boolean':
      return value ? 'true' : 'false'
    case 'string':
      return JSON.stringify(value)
    case 'number':
      if (!Number.isSafeInteger(value)) {
        throw new LosslessJsonError('Only safe integers may be sent as numbers; use JsonRawNumber for decimals')
      }
      return String(value)
    case 'object':
      if (value === null) return 'null'
      return Array.isArray(value) ? writeArray(value, depth) : writeObject(value, depth)
    default:
      throw new LosslessJsonError(`Cannot serialise a ${typeof value}`)
  }
}

function writeArray(items: readonly unknown[], depth: number): string {
  return `[${items.map((item) => (item === undefined ? 'null' : write(item, depth + 1))).join(',')}]`
}

function writeObject(value: object, depth: number): string {
  const proto: unknown = Object.getPrototypeOf(value)
  if (proto !== Object.prototype && proto !== null) {
    throw new LosslessJsonError('Only plain objects can be serialised')
  }
  const parts: string[] = []
  for (const [key, item] of Object.entries(value)) {
    if (item !== undefined) {
      parts.push(`${JSON.stringify(key)}:${write(item, depth + 1)}`)
    }
  }
  return `{${parts.join(',')}}`
}
