/**
 * Exact money values for the whole frontend (Phase 11, D-4).
 *
 * Money is never held in a JavaScript `number`. A binary double cannot store most
 * decimals exactly (0.29 is really 0.28999999999999998), and float sums drift
 * (0.1 + 0.2 !== 0.3). So:
 *
 * - Values travel through the app as canonical decimal strings ({@link Decimal}), e.g. "1250.40".
 * - All arithmetic runs on `bigint` cents ({@link toCents} / {@link fromCents}).
 * - API text is read without a float step (see `shared/api/lossless-json.ts`).
 * - User input is checked as text ({@link parseMoneyInput}); nothing is ever rounded.
 *
 * The backend stores money as DECIMAL(15,2): at most 13 integer and 2 fraction digits.
 */

declare const decimalBrand: unique symbol

/**
 * A canonical 2-decimal value: optional "-", integer digits without leading zeros,
 * ".", exactly two digits. Zero is always "0.00", never "-0.00".
 * Used for money and for backend percentages (both have scale 2).
 */
export type Decimal = string & { readonly [decimalBrand]: true }

/** Fraction digits of every money value. */
export const MONEY_SCALE = 2

/** Largest number of integer digits a user may type (DECIMAL(15,2) → 13). */
export const MAX_INPUT_INTEGER_DIGITS = 13

const CENTS_PER_UNIT = 10n ** BigInt(MONEY_SCALE)
const MAX_LITERAL_DIGITS = 64
const MAX_EXPONENT = 64

/** A JSON-style number literal as the backend may write it. */
const API_LITERAL = /^(-)?(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/

/** What a user may type: optional "-", digits, optional "." with at least one digit. */
const INPUT_LITERAL = /^(-)?(\d+)(?:\.(\d+))?$/

/** Thrown when a value is not a valid decimal literal. */
export class DecimalFormatError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DecimalFormatError'
  }
}

/** Thrown when a value would need rounding to fit two decimals, or already lost precision. */
export class DecimalPrecisionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DecimalPrecisionError'
  }
}

/**
 * Converts an exact decimal literal to integer cents.
 *
 * Accepts the forms the backend can emit: "12", "-12.5", "12.340", "1.5E+2".
 * Throws instead of rounding when a non-zero digit sits below the cent.
 *
 * @param literal decimal text, never a float
 * @returns the value in cents
 */
export function toCents(literal: string): bigint {
  const match = API_LITERAL.exec(literal)
  if (!match) {
    throw new DecimalFormatError('Not a decimal number')
  }
  const [, sign, integerPart = '', fractionPart = '', exponentPart = '0'] = match
  if (integerPart.length + fractionPart.length > MAX_LITERAL_DIGITS) {
    throw new DecimalFormatError('Decimal number has too many digits')
  }
  const exponent = Number.parseInt(exponentPart, 10)
  if (Math.abs(exponent) > MAX_EXPONENT) {
    throw new DecimalFormatError('Decimal exponent is out of range')
  }

  const digits = BigInt(integerPart + fractionPart)
  const shift = exponent - fractionPart.length + MONEY_SCALE
  let cents: bigint
  if (shift >= 0) {
    cents = digits * 10n ** BigInt(shift)
  } else {
    const divisor = 10n ** BigInt(-shift)
    if (digits % divisor !== 0n) {
      throw new DecimalPrecisionError('Value has more than two decimal places')
    }
    cents = digits / divisor
  }
  return sign === '-' ? -cents : cents
}

/**
 * Formats integer cents as a canonical {@link Decimal}.
 *
 * @param cents value in cents
 * @returns canonical decimal string
 */
export function fromCents(cents: bigint): Decimal {
  const negative = cents < 0n
  const magnitude = negative ? -cents : cents
  const units = magnitude / CENTS_PER_UNIT
  const rest = (magnitude % CENTS_PER_UNIT).toString().padStart(MONEY_SCALE, '0')
  return `${negative ? '-' : ''}${units.toString()}.${rest}` as Decimal
}

/**
 * Normalises an exact decimal literal (API text) to a canonical {@link Decimal}.
 *
 * @param literal decimal text
 * @returns canonical decimal string
 */
export function toDecimal(literal: string): Decimal {
  return fromCents(toCents(literal))
}

/**
 * Reads a money field from a parsed API response.
 *
 * The lossless JSON parser yields a string for every literal with a fraction or exponent,
 * and a `number` only for safe integers. A non-integer or unsafe `number` here means a
 * float step already happened, so it is refused instead of guessed.
 *
 * @param value field value from `parseLosslessJson`
 * @returns canonical decimal string
 */
export function decimalFromWire(value: string | number): Decimal {
  if (typeof value === 'string') {
    return toDecimal(value)
  }
  if (!Number.isSafeInteger(value)) {
    throw new DecimalPrecisionError('Money value arrived as a float; precision cannot be trusted')
  }
  return fromCents(BigInt(value) * CENTS_PER_UNIT)
}

/** Exact sum of two values. */
export function addDecimal(a: Decimal, b: Decimal): Decimal {
  return fromCents(toCents(a) + toCents(b))
}

/** Exact difference `a - b`. */
export function subtractDecimal(a: Decimal, b: Decimal): Decimal {
  return fromCents(toCents(a) - toCents(b))
}

/** Exact sum of a list; "0.00" for an empty list. */
export function sumDecimals(values: readonly Decimal[]): Decimal {
  return fromCents(values.reduce((total, value) => total + toCents(value), 0n))
}

/** The value with the opposite sign; zero stays "0.00". */
export function negateDecimal(value: Decimal): Decimal {
  return fromCents(-toCents(value))
}

/** -1, 0 or 1 as `a` is less than, equal to or greater than `b`. */
export function compareDecimal(a: Decimal, b: Decimal): -1 | 0 | 1 {
  const diff = toCents(a) - toCents(b)
  if (diff === 0n) return 0
  return diff < 0n ? -1 : 1
}

/** True when the value is below zero. */
export function isNegativeDecimal(value: Decimal): boolean {
  return toCents(value) < 0n
}

/** True when the value is zero. */
export function isZeroDecimal(value: Decimal): boolean {
  return toCents(value) === 0n
}

/** Why a typed money value was refused. */
export type MoneyInputError = 'REQUIRED' | 'FORMAT' | 'NEGATIVE_NOT_ALLOWED' | 'TOO_LARGE' | 'TOO_PRECISE'

/** Result of {@link parseMoneyInput}. */
export type MoneyInputResult =
  | { readonly ok: true; readonly value: Decimal }
  | { readonly ok: false; readonly error: MoneyInputError }

/** Options for {@link parseMoneyInput}. */
export interface MoneyInputOptions {
  /** Allow a leading "-" (e.g. a credit account's opening balance). Default false. */
  readonly allowNegative?: boolean
}

/**
 * Checks money typed by a user. Works on text only and never rounds.
 *
 * Accepted: optional "-" (when allowed), up to 13 integer digits, optionally "." and 1–2 digits.
 * Leading zeros and surrounding spaces are ignored. Grouping commas, "+", exponents and
 * non-ASCII digits are refused.
 *
 * @param text raw input text
 * @param options sign rules
 * @returns the canonical value, or the reason it was refused
 */
export function parseMoneyInput(text: string, options: MoneyInputOptions = {}): MoneyInputResult {
  const trimmed = text.trim()
  if (trimmed === '') {
    return { ok: false, error: 'REQUIRED' }
  }
  const match = INPUT_LITERAL.exec(trimmed)
  if (!match) {
    return { ok: false, error: 'FORMAT' }
  }
  const [, sign, integerPart = '', fractionPart = ''] = match
  if (sign === '-' && options.allowNegative !== true) {
    return { ok: false, error: 'NEGATIVE_NOT_ALLOWED' }
  }
  const significantInteger = integerPart.replace(/^0+(?=\d)/, '')
  if (significantInteger.length > MAX_INPUT_INTEGER_DIGITS) {
    return { ok: false, error: 'TOO_LARGE' }
  }
  if (fractionPart.length > MONEY_SCALE) {
    return { ok: false, error: 'TOO_PRECISE' }
  }
  const literal = `${sign ?? ''}${significantInteger}${fractionPart === '' ? '' : `.${fractionPart}`}`
  return { ok: true, value: toDecimal(literal) }
}

/** Plain-language message for a refused money input. */
export function describeMoneyInputError(error: MoneyInputError): string {
  switch (error) {
    case 'REQUIRED':
      return 'Enter an amount.'
    case 'FORMAT':
      return 'Use digits and an optional point, like 1250.40.'
    case 'NEGATIVE_NOT_ALLOWED':
      return 'Enter a positive amount.'
    case 'TOO_LARGE':
      return 'Use at most 13 digits before the point.'
    case 'TOO_PRECISE':
      return 'Use at most 2 digits after the point.'
  }
}
