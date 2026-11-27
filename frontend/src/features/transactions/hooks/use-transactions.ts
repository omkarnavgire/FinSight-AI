import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { QUERY_ROOTS } from '@/shared/api/query-roots'
import type { TransactionFilter } from '@/shared/types/api'
import { listTransactions } from '../api/transactions-api'

export const TRANSACTIONS_PAGE_SIZE = 20

/** Query key for one filtered page. */
export function transactionsKey(filter: TransactionFilter, page: number) {
  return [...QUERY_ROOTS.transactions, 'list', filter, page] as const
}

/** One filtered page of transactions; the previous page stays visible while the next loads. */
export function useTransactions(filter: TransactionFilter, page: number) {
  return useQuery({
    queryKey: transactionsKey(filter, page),
    queryFn: () => listTransactions(filter, page, TRANSACTIONS_PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
}
