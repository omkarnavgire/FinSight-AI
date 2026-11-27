import { useMemo } from 'react'
import { useAllAccounts } from '@/features/accounts'
import { useTransactions } from '@/features/transactions'
import { formatDate } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import { isNegativeDecimal } from '@/shared/money/decimal'
import type { TransactionFilter } from '@/shared/types/api'
import { CardNote, QueryBody, SummaryCard } from './summary-card'

const SHOWN = 5
/** No filters: shares the cache with the unfiltered first page of the Transactions page. */
const NO_FILTER: TransactionFilter = {}

/** The newest transactions, each in its own account's currency. No total is shown. */
export function RecentTransactionsCard() {
  const transactions = useTransactions(NO_FILTER, 0)
  const accounts = useAllAccounts()
  const byId = useMemo(() => new Map((accounts.data ?? []).map((account) => [account.id, account])), [accounts.data])

  return (
    <SummaryCard title="Recent transactions" to="/transactions" linkLabel="Open transactions">
      <QueryBody
        query={transactions}
        label="transactions"
        isEmpty={(data) => data.content.length === 0}
        empty={<CardNote>No transactions yet.</CardNote>}
      >
        {(data) => (
          <ul className="flex flex-col divide-y divide-gray-800">
            {data.content.slice(0, SHOWN).map((transaction) => {
              const account = byId.get(transaction.accountId)
              const expense = isNegativeDecimal(transaction.amount)
              return (
                <li key={transaction.id} className="flex items-baseline justify-between gap-3 py-2">
                  <span className="min-w-0">
                    <span className="block truncate text-sm">{transaction.description ?? '—'}</span>
                    <span className="block text-xs text-gray-400">
                      {formatDate(transaction.transactionDate)} · {account?.name ?? `Account #${transaction.accountId}`}
                    </span>
                  </span>
                  <span className={`whitespace-nowrap text-sm tabular-nums ${expense ? 'text-gray-100' : 'text-emerald-300'}`}>
                    <span className="sr-only">{expense ? 'Expense ' : 'Income '}</span>
                    {formatMoney(transaction.amount, account?.currency)}
                  </span>
                </li>
              )
            })}
          </ul>
        )}
      </QueryBody>
    </SummaryCard>
  )
}
