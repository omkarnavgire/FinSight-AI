/**
 * Fake Axios adapter for tests. Replies with raw text, like the browser does, so the real
 * lossless transforms and interceptors run unchanged. Status checks follow Axios `settle`.
 */
import {
  AxiosError,
  AxiosHeaders,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

export interface MockReply {
  status: number
  /** Raw response text. Default: empty. */
  body?: string
  headers?: Record<string, string>
}

export type MockHandler = (config: InternalAxiosRequestConfig) => MockReply | Promise<MockReply>

/** Builds an adapter that answers every request with `handler`. */
export function mockAdapter(handler: MockHandler): AxiosAdapter {
  return async (config) => {
    const reply = await handler(config)
    const response: AxiosResponse<string> = {
      data: reply.body ?? '',
      status: reply.status,
      statusText: '',
      headers: AxiosHeaders.from(reply.headers ?? {}),
      config,
      request: {},
    }
    const validate = config.validateStatus
    if (validate === undefined || validate === null || validate(reply.status)) {
      return response
    }
    const code = reply.status >= 500 ? AxiosError.ERR_BAD_RESPONSE : AxiosError.ERR_BAD_REQUEST
    throw new AxiosError(`Request failed with status code ${reply.status}`, code, config, {}, response)
  }
}

/** A network failure (no response), as Axios reports it. */
export function networkError(config: InternalAxiosRequestConfig): never {
  throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config, {})
}

/** The `Authorization` header a request was sent with, or `null`. */
export function authHeader(config: InternalAxiosRequestConfig): string | null {
  const value = AxiosHeaders.from(config.headers).get('Authorization')
  return typeof value === 'string' ? value : null
}

/** A promise with its resolver, for holding a reply until a test is ready. */
export function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve: () => void = () => undefined
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

/** JSON body of a valid `TokenResponse`. */
export function tokenBody(token: string): string {
  return JSON.stringify({ accessToken: token, tokenType: 'Bearer', expiresIn: 900 })
}
