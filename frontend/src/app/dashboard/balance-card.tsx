import { useAllAccounts } from '@/features/accounts'
import { formatMoney } from '@/shared/format/format-money'
import { totalsByCurrency } from '@/shared/money/group-by-currency'
import { CardNote, QueryBody, SummaryCard } from './summary-card'

/** Total balance of active accounts, one total per currency (never one combined total). */
export function BalanceCard() {
  const accounts = useAllAccounts()
  return (
    <SummaryCard title="Total balance" to="/accounts" linkLabel="Open accounts">
      <QueryBody
        query={accounts}
        label="balances"
        isEmpty={(data) => !data.some((account) => account.active)}
        empty={<CardNote>No active accounts yet.</CardNote>}
      >
        {(data) => {
          const totals = totalsByCurrency(
            data.filter((account) => account.active),
            (account) => account.currency,
            (account) => account.balance,
          )
          return (
            <>
              <ul className="flex flex-col gap-1">
                {totals.map((total) => (
                  <li key={total.currency} className="flex items-baseline justify-between gap-3">
                    <span className="text-sm text-gray-400">{total.currency}</span>
                    <span className="text-lg font-semibold tabular-nums">{formatMoney(total.total, total.currency)}</span>
                  </li>
                ))}
              </ul>
              {totals.length > 1 ? <CardNote>Shown per currency. Amounts in different currencies are not added.</CardNote> : null}
            </>
          )
        }}
      </QueryBody>
    </SummaryCard>
  )
}
