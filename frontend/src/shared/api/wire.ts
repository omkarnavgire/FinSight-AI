/**
 * Strict readers for parsed API bodies (output of `parseLosslessJson`).
 *
 * Responses are checked field by field instead of being cast, so a contract change shows up as a
 * clear `WireFormatError` (rendered as a generic error state) instead of wrong data on screen.
 * Money goes through `decimalFromWire`, which keeps the exact decimal text.
 */
import { type Decimal, decimalFromWire } from '@/shared/money/decimal'
import type { PageResponse } from '@/shared/types/api'

/** The response body does not have the shape the frontend expects. */
export class WireFormatError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'WireFormatError'
  }
}

export type WireRecord = Readonly<Record<string, unknown>>

/** The value as a plain object. */
export function asRecord(value: unknown, what: string): WireRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new WireFormatError(`${what} is not an object`)
  }
  return value as WireRecord
}

function field(record: WireRecord, key: string): unknown {
  if (!Object.prototype.hasOwnProperty.call(record, key)) {
    throw new WireFormatError(`Missing field "${key}"`)
  }
  return record[key]
}

export function wireString(record: WireRecord, key: string): string {
  const value = field(record, key)
  if (typeof value !== 'string') throw new WireFormatError(`Field "${key}" is not a string`)
  return value
}

export function wireNullableString(record: WireRecord, key: string): string | null {
  const value = field(record, key)
  if (value === null) return null
  if (typeof value !== 'string') throw new WireFormatError(`Field "${key}" is not a string`)
  return value
}

/** A whole number (ids, counts, years). */
export function wireInteger(record: WireRecord, key: string): number {
  const value = field(record, key)
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new WireFormatError(`Field "${key}" is not a whole number`)
  }
  return value
}

export function wireBoolean(record: WireRecord, key: string): boolean {
  const value = field(record, key)
  if (typeof value !== 'boolean') throw new WireFormatError(`Field "${key}" is not a boolean`)
  return value
}

/** A BigDecimal field, kept exact. */
export function wireDecimal(record: WireRecord, key: string): Decimal {
  const value = field(record, key)
  if (typeof value !== 'string' && typeof value !== 'number') {
    throw new WireFormatError(`Field "${key}" is not a number`)
  }
  return decimalFromWire(value)
}

/** One of a fixed set of enum names. */
export function wireEnum<T extends string>(record: WireRecord, key: string, allowed: readonly T[]): T {
  const value = field(record, key)
  const match = allowed.find((option) => option === value)
  if (match === undefined) throw new WireFormatError(`Field "${key}" has an unknown value`)
  return match
}

/** An array field, each item read by `readItem`. */
export function wireArray<T>(record: WireRecord, key: string, readItem: (item: unknown) => T): T[] {
  const value = field(record, key)
  if (!Array.isArray(value)) throw new WireFormatError(`Field "${key}" is not a list`)
  return value.map(readItem)
}

/** A `PageResponse<T>` body. */
export function wirePage<T>(body: unknown, readItem: (item: unknown) => T): PageResponse<T> {
  const record = asRecord(body, 'Page')
  return {
    content: wireArray(record, 'content', readItem),
    page: wireInteger(record, 'page'),
    size: wireInteger(record, 'size'),
    totalElements: wireInteger(record, 'totalElements'),
    totalPages: wireInteger(record, 'totalPages'),
    last: wireBoolean(record, 'last'),
  }
}
