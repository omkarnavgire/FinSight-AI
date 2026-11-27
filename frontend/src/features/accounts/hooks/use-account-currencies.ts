import { useMemo } from 'react'
import { type CurrencyMode, currencyMode } from '@/shared/money/group-by-currency'
import { useAllAccounts } from './use-all-accounts'

export interface AccountCurrencies {
  /** `undefined` while loading or after an error: callers must not show money totals then. */
  mode: CurrencyMode | undefined
  isPending: boolean
  error: unknown
  refetch: () => void
}

/**
 * Which currencies the user's accounts use (D-5).
 *
 * @param activeOnly count active accounts only (the forecast uses active balances); default: all,
 *   because transactions on inactive accounts still appear in analytics and budgets
 */
export function useAccountCurrencies(activeOnly = false): AccountCurrencies {
  const query = useAllAccounts()
  const mode = useMemo(() => {
    if (query.data === undefined) return undefined
    const accounts = activeOnly ? query.data.filter((account) => account.active) : query.data
    return currencyMode(accounts.map((account) => account.currency))
  }, [query.data, activeOnly])
  return {
    mode,
    isPending: query.isPending,
    error: query.error,
    refetch: () => void query.refetch(),
  }
}
