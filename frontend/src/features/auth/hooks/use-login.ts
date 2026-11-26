import { useMutation } from '@tanstack/react-query'
import { login } from '../api/auth-api'
import { useAuth } from './use-auth'

/** Logs in; on success the token goes to memory and `PublicOnlyRoute` moves the user on. Never retried. */
export function useLogin() {
  const { establishSession } = useAuth()
  return useMutation({ mutationFn: login, onSuccess: establishSession, retry: false })
}
