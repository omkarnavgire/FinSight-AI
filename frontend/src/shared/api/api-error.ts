/**
 * Turns any API failure into one safe, displayable shape (T-64, plan section 6).
 *
 * - Reads RFC 7807 `ProblemDetail` bodies from `GlobalExceptionHandler`.
 * - Splits validation `errors` ("field: message") into per-field messages.
 * - Reads `Retry-After` (seconds) from 429 responses.
 * - Never shows server text for 5xx, and never shows raw non-JSON bodies.
 * Messages are plain text; React escapes them when rendering.
 */
import axios, { AxiosHeaders } from 'axios'
import type { ProblemDetail } from '@/shared/types/api'

export type ApiErrorKind = 'http' | 'network' | 'unknown'

export interface ApiError {
  kind: ApiErrorKind
  /** HTTP status, or `null` when no response arrived. */
  status: number | null
  /** One-line message for the user. */
  message: string
  /** First message per field from a 400 validation response. */
  fieldErrors: Readonly<Record<string, string>>
  /** Seconds from `Retry-After`, or `null`. */
  retryAfterSeconds: number | null
}

const DEFAULT_MESSAGES: Readonly<Record<number, string>> = {
  400: 'Some details are not valid. Please check and try again.',
  401: 'Your session has ended. Please log in again.',
  403: 'You do not have access to this.',
  404: 'We could not find that.',
  409: 'This conflicts with data you already have.',
}

const SERVER_ERROR_MESSAGE = 'Something went wrong on our side. Please try again.'
const NETWORK_MESSAGE = "We can't reach the server. Check your connection and try again."
const UNKNOWN_MESSAGE = 'Something went wrong. Please try again.'

/**
 * Converts a thrown value to an {@link ApiError}.
 *
 * @param error anything caught from an API call
 * @returns a safe description
 */
export function toApiError(error: unknown): ApiError {
  if (!axios.isAxiosError(error)) {
    return build('unknown', null, UNKNOWN_MESSAGE)
  }
  const response = error.response
  if (response === undefined) {
    return build('network', null, NETWORK_MESSAGE)
  }
  const status = response.status
  const problem = readProblem(response.data)

  if (status === 429) {
    const seconds = readRetryAfter(readHeader(response.headers, 'retry-after'))
    return { ...build('http', status, tooManyRequestsMessage(seconds)), retryAfterSeconds: seconds }
  }
  if (status >= 500) {
    return build('http', status, SERVER_ERROR_MESSAGE)
  }
  const message = problem?.detail ?? DEFAULT_MESSAGES[status] ?? UNKNOWN_MESSAGE
  return { ...build('http', status, message), fieldErrors: readFieldErrors(problem?.errors) }
}

/**
 * Message for a 429 response.
 *
 * @param seconds value of `Retry-After`, or `null`
 */
export function tooManyRequestsMessage(seconds: number | null): string {
  if (seconds === null) return 'Too many tries. Please wait and try again.'
  if (seconds < 60) return 'Too many tries. Try again in a minute.'
  const minutes = Math.ceil(seconds / 60)
  return `Too many tries. Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`
}

function build(kind: ApiErrorKind, status: number | null, message: string): ApiError {
  return { kind, status, message, fieldErrors: {}, retryAfterSeconds: null }
}

function readProblem(data: unknown): ProblemDetail | null {
  if (typeof data !== 'object' || data === null) return null
  const { status, detail, errors } = data as Record<string, unknown>
  if (typeof status !== 'number') return null
  return {
    status,
    detail: typeof detail === 'string' && detail.trim() !== '' ? detail : undefined,
    errors: Array.isArray(errors) ? errors.filter((item): item is string => typeof item === 'string') : undefined,
  }
}

function readFieldErrors(errors: readonly string[] | undefined): Readonly<Record<string, string>> {
  const entries: [string, string][] = []
  const seen = new Set<string>()
  for (const item of errors ?? []) {
    const split = item.indexOf(': ')
    if (split <= 0) continue
    const field = item.slice(0, split)
    if (seen.has(field)) continue
    seen.add(field)
    entries.push([field, item.slice(split + 2)])
  }
  return Object.fromEntries(entries)
}

function readRetryAfter(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d{1,9}$/.test(value.trim())) return null
  return Number.parseInt(value.trim(), 10)
}

/** One response header, matched case-insensitively; `undefined` when absent or unreadable. */
function readHeader(headers: unknown, name: string): unknown {
  if (headers instanceof AxiosHeaders) return headers.get(name)
  if (typeof headers !== 'object' || headers === null) return undefined
  const wanted = name.toLowerCase()
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === wanted) return value
  }
  return undefined
}
