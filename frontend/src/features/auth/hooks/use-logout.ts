import { useMutation } from '@tanstack/react-query'
import { logout } from '../api/auth-api'
import { useAuth } from './use-auth'

/**
 * Logs out. Local state (token, cached data) is cleared whether or not the server call succeeds,
 * so a network error never leaves the user looking signed in.
 */
export function useLogout() {
  const { endSession } = useAuth()
  return useMutation({ mutationFn: logout, onSettled: endSession, retry: false })
}
