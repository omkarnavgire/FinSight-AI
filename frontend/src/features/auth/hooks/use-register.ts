import { useMutation } from '@tanstack/react-query'
import { register } from '../api/auth-api'
import { useAuth } from './use-auth'

/** Registers and signs in with the returned token. Never retried. */
export function useRegister() {
  const { establishSession } = useAuth()
  return useMutation({ mutationFn: register, onSuccess: establishSession, retry: false })
}
