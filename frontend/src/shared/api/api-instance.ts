/**
 * Shared Axios setup for `apiClient` and `refreshClient` (T-64).
 *
 * Both instances read and write JSON losslessly (D-4): responses are fetched as text and parsed
 * with `parseLosslessJson`; request bodies are written with `stringifyLosslessJson`.
 * `withCredentials` lets the browser send the HttpOnly refresh cookie on `/auth/*` calls;
 * JavaScript never reads that cookie.
 */
import axios, { type AxiosInstance } from 'axios'
import { LosslessJsonError, parseLosslessJson, stringifyLosslessJson } from './lossless-json'

export const API_BASE_URL = '/api/v1'

/** Parses a JSON body; an empty body becomes `undefined`, a non-JSON body stays raw text. */
export function parseResponseBody(data: unknown): unknown {
  if (typeof data !== 'string') return data
  if (data.trim() === '') return undefined
  try {
    return parseLosslessJson(data)
  } catch (error) {
    if (error instanceof LosslessJsonError) return data
    throw error
  }
}

/** Serialises a request body; `undefined` means no body. */
export function serialiseRequestBody(data: unknown): string | undefined {
  return data === undefined ? undefined : stringifyLosslessJson(data)
}

/** Creates an Axios instance with the shared JSON and cookie settings. */
export function createApiInstance(): AxiosInstance {
  return axios.create({
    baseURL: API_BASE_URL,
    withCredentials: true,
    responseType: 'text',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    transformRequest: [serialiseRequestBody],
    transformResponse: [parseResponseBody],
  })
}
