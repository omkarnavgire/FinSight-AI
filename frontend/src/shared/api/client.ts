/**
 * Central Axios instance for all API calls.
 *
 * Rules (coding standards):
 * - Access token kept in memory only — never in localStorage.
 * - 401 responses trigger a single refresh call; concurrent 401s are
 *   gated by a module-level refreshPromise (single-flight pattern, AC-SF-1/2/3).
 * - All API calls in the application must use this instance, never raw fetch.
 */
import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosError } from 'axios'

export const API_BASE_URL = '/api/v1'

/** In-memory access token — never persisted to localStorage. */
let accessToken: string | null = null

/** Single-flight gate: prevents duplicate refresh calls on concurrent 401s. */
let refreshPromise: Promise<string> | null = null

export function setAccessToken(token: string): void {
  accessToken = token
}

export function clearAccessToken(): void {
  accessToken = null
}

export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true, // sends the HttpOnly refresh-token cookie
  headers: {
    'Content-Type': 'application/json',
  },
})

// --- Request interceptor: attach bearer token ---
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

// --- Response interceptor: single-flight 401 refresh ---
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean }

    if (error.response?.status !== 401 || originalRequest._retry) {
      return Promise.reject(error)
    }

    originalRequest._retry = true

    try {
      // Gate: if a refresh is already in flight, join it rather than firing again.
      if (!refreshPromise) {
        refreshPromise = apiClient
          .post<{ accessToken: string }>('/auth/refresh')
          .then((res) => {
            setAccessToken(res.data.accessToken)
            return res.data.accessToken
          })
          .finally(() => {
            refreshPromise = null
          })
      }

      const newToken = await refreshPromise
      originalRequest.headers.Authorization = `Bearer ${newToken}`
      return apiClient(originalRequest)
    } catch {
      clearAccessToken()
      refreshPromise = null
      window.location.href = '/login'
      return Promise.reject(error)
    }
  },
)
