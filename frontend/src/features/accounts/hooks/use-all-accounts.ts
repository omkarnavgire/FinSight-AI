import { useQuery } from '@tanstack/react-query'
import { listAllAccounts } from '../api/accounts-api'
import { accountKeys } from './account-keys'

/** Every account of the signed-in user (active and inactive). Used for currency checks and totals. */
export function useAllAccounts() {
  return useQuery({ queryKey: accountKeys.everything, queryFn: listAllAccounts })
}
