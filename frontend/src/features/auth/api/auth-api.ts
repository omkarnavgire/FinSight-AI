/**
 * Auth endpoints (`/api/v1/auth`, T-65). The refresh token is set and cleared by the backend as an
 * HttpOnly cookie; only the access token is read from the body.
 */
import { apiClient } from '@/shared/api/client'
import { readAccessToken } from '@/shared/api/refresh'
import type { LoginRequest, RegisterRequest } from '@/shared/types/api'

/** `POST /auth/login`. Resolves to the access token. 401 = wrong credentials; 429 = rate limit. */
export async function login(request: LoginRequest): Promise<string> {
  const response = await apiClient.post<unknown>('/auth/login', request)
  return readAccessToken(response.data)
}

/** `POST /auth/register` (201). Resolves to the access token. 409 = email already used. */
export async function register(request: RegisterRequest): Promise<string> {
  const response = await apiClient.post<unknown>('/auth/register', request)
  return readAccessToken(response.data)
}

/** `POST /auth/logout` (204). Revokes the refresh token and clears its cookie. */
export async function logout(): Promise<void> {
  await apiClient.post('/auth/logout')
}
