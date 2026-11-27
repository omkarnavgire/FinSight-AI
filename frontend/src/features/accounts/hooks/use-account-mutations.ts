import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createAccount, deactivateAccount } from '../api/accounts-api'
import { accountKeys } from './account-keys'

/** Creates an account, then refreshes every accounts query. Never retried. */
export function useCreateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createAccount,
    retry: false,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: accountKeys.all }),
  })
}

/** Deactivates an account, then refreshes every accounts query. Never retried. */
export function useDeactivateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deactivateAccount,
    retry: false,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: accountKeys.all }),
  })
}
