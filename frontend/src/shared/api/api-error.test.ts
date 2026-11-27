import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { toApiError, tooManyRequestsMessage } from './api-error'
import { parseResponseBody } from './api-instance'

const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig

/** An Axios error whose body went through the real lossless response transform. */
function httpError(status: number, body: string, headers: Record<string, string> = {}): AxiosError {
  return new AxiosError('failed', AxiosError.ERR_BAD_REQUEST, config, {}, {
    status,
    statusText: '',
    data: parseResponseBody(body),
    headers: AxiosHeaders.from(headers),
    config,
  })
}

describe('toApiError', () => {
  it('uses the ProblemDetail detail for 4xx', () => {
    const error = httpError(409, '{"status":409,"title":"Conflict","detail":"An account with this email already exists"}')
    expect(toApiError(error)).toMatchObject({
      kind: 'http',
      status: 409,
      message: 'An account with this email already exists',
    })
  })

  it('splits validation errors per field, keeping the first message', () => {
    const body = JSON.stringify({
      status: 400,
      title: 'Validation Failed',
      detail: 'One or more fields are invalid',
      errors: ['email: Email must be a valid email address', 'password: Password is required', 'email: second', 'broken'],
    })
    expect(toApiError(httpError(400, body)).fieldErrors).toEqual({
      email: 'Email must be a valid email address',
      password: 'Password is required',
    })
  })

  it('falls back to a default message without a detail', () => {
    expect(toApiError(httpError(404, '{"status":404}')).message).toBe('We could not find that.')
    expect(toApiError(httpError(403, '')).message).toBe('You do not have access to this.')
  })

  it('never shows server text for 5xx', () => {
    const error = httpError(500, '{"status":500,"detail":"java.lang.NullPointerException at ..."}')
    expect(toApiError(error).message).toBe('Something went wrong on our side. Please try again.')
  })

  it('never shows a raw non-JSON body', () => {
    const error = httpError(502, '<html><body>Bad Gateway</body></html>')
    expect(toApiError(error).message).not.toContain('<')
    expect(toApiError(httpError(418, '<b>teapot</b>')).message).toBe('Something went wrong. Please try again.')
  })

  it('reads Retry-After seconds on 429', () => {
    const error = httpError(429, '{"status":429}', { 'Retry-After': '840' })
    expect(toApiError(error)).toMatchObject({
      status: 429,
      retryAfterSeconds: 840,
      message: 'Too many tries. Try again in 14 minutes.',
    })
  })

  it('ignores a malformed Retry-After', () => {
    expect(toApiError(httpError(429, '', { 'Retry-After': 'soon' })).retryAfterSeconds).toBeNull()
  })

  it('reports network failures', () => {
    const error = new AxiosError('Network Error', AxiosError.ERR_NETWORK, config, {})
    expect(toApiError(error)).toMatchObject({ kind: 'network', status: null })
  })

  it('handles non-Axios values', () => {
    expect(toApiError(new Error('x'))).toMatchObject({ kind: 'unknown', status: null })
    expect(toApiError('x')).toMatchObject({ kind: 'unknown' })
  })
})

describe('tooManyRequestsMessage', () => {
  it('words the wait in minutes', () => {
    expect(tooManyRequestsMessage(null)).toBe('Too many tries. Please wait and try again.')
    expect(tooManyRequestsMessage(30)).toBe('Too many tries. Try again in a minute.')
    expect(tooManyRequestsMessage(60)).toBe('Too many tries. Try again in 1 minute.')
    expect(tooManyRequestsMessage(61)).toBe('Too many tries. Try again in 2 minutes.')
  })
})
