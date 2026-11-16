/**
 * Shared request/response types for all API calls.
 *
 * Rules (coding standards):
 * - No `any` — use `unknown` and narrow, or define a proper type.
 * - Request/response types live here, not in components.
 */

/** RFC 7807 problem detail returned by the global @RestControllerAdvice. */
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance: string
}

/** Generic paginated list response. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}
