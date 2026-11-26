import { useMemo } from 'react'
import { formatMoney } from '@/shared/format/format-money'
import { currencyMode, totalsByCurrency } from '@/shared/money/group-by-currency'
import { ErrorState } from '@/shared/ui/error-state'
import { MixedCurrencyNotice } from '@/shared/ui/mixed-currency-notice'
import { Spinner } from '@/shared/ui/spinner'
import { useAllAccounts } from '../hooks/use-all-accounts'

/**
 * Total balance of active accounts, one total per currency (D-5). Balances in different
 * currencies are never added together; when there are several, a notice says why there is no
 * single total.
 */
export function CurrencyTotals() {
  const query = useAllAccounts()
  const active = useMemo(() => (query.data ?? []).filter((account) => account.active), [query.data])
  const totals = useMemo(
    () => totalsByCurrency(active, (account) => account.currency, (account) => account.balance),
    [active],
  )
  const mode = currencyMode(totals.map((total) => total.currency))

  if (query.isPending) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-gray-300">
        <Spinner /> Loading totals…
      </p>
    )
  }
  if (query.isError) {
    return <ErrorState error={query.error} title="Could not load totals" onRetry={() => void query.refetch()} />
  }
  if (totals.length === 0) {
    return <p className="text-sm text-gray-400">No active accounts, so there is no total yet.</p>
  }

  return (
    <section aria-labelledby="totals-title" className="flex flex-col gap-3">
      <h2 id="totals-title" className="text-lg font-semibold">
        Total balance
      </h2>
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {totals.map((total) => (
          <li key={total.currency} className="rounded-lg border border-gray-800 bg-gray-900 p-4">
            <p className="text-sm text-gray-400">
              {total.currency} · {total.count} active account{total.count === 1 ? '' : 's'}
            </p>
            <p className="mt-1 text-xl font-semibold tabular-nums">{formatMoney(total.total, total.currency)}</p>
          </li>
        ))}
      </ul>
      {mode.kind === 'mixed' ? <MixedCurrencyNotice currencies={mode.currencies} subject="your total balance" /> : null}
    </section>
  )
}
