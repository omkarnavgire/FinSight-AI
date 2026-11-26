import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listAccounts } from '../api/accounts-api'
import { accountKeys } from './account-keys'

/** One page of accounts. Keeps the previous page on screen while the next one loads. */
export function useAccounts(page: number, size = 20) {
  return useQuery({
    queryKey: accountKeys.page(page, size),
    queryFn: () => listAccounts(page, size),
    placeholderData: keepPreviousData,
  })
}
