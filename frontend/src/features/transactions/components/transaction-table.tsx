import { CATEGORY_LABELS } from '@/shared/domain/categories'
import { formatDate } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import { isNegativeDecimal } from '@/shared/money/decimal'
import type { AccountDto, TransactionDto } from '@/shared/types/api'
import { Button } from '@/shared/ui/button'

export type TransactionAction = 'edit' | 'category' | 'delete'

export interface TransactionTableProps {
  transactions: readonly TransactionDto[]
  /** Accounts by id, for names and currencies. */
  accounts: ReadonlyMap<number, AccountDto>
  onAction: (action: TransactionAction, transaction: TransactionDto) => void
}

function Amount({ transaction, currency }: { transaction: TransactionDto; currency: string | undefined }) {
  const expense = isNegativeDecimal(transaction.amount)
  return (
    <span className={`tabular-nums ${expense ? 'text-gray-100' : 'text-emerald-300'}`}>
      <span className="sr-only">{expense ? 'Expense ' : 'Income '}</span>
      {formatMoney(transaction.amount, currency)}
    </span>
  )
}

function Actions({ transaction, onAction }: { transaction: TransactionDto; onAction: TransactionTableProps['onAction'] }) {
  const label = transaction.description ?? `transaction ${transaction.id}`
  return (
    <div className="flex flex-wrap justify-end gap-1">
      <Button variant="ghost" onClick={() => onAction('edit', transaction)} ariaLabel={`Edit ${label}`}>Edit</Button>
      <Button variant="ghost" onClick={() => onAction('category', transaction)} ariaLabel={`Change category of ${label}`}>Category</Button>
      <Button variant="ghost" onClick={() => onAction('delete', transaction)} ariaLabel={`Delete ${label}`}>Delete</Button>
    </div>
  )
}

function CategoryText({ transaction }: { transaction: TransactionDto }) {
  return (
    <>
      {CATEGORY_LABELS[transaction.category]}
      {transaction.manual ? <span className="text-gray-400"> (set by you)</span> : null}
    </>
  )
}

/** Transactions as a table from `md` up and as cards below. Each amount uses its account's currency. */
export function TransactionTable({ transactions, accounts, onAction }: TransactionTableProps) {
  return (
    <>
      <table className="hidden w-full border-collapse text-left text-sm md:table">
        <caption className="sr-only">Transactions</caption>
        <thead>
          <tr className="border-b border-gray-700 text-gray-300">
            <th scope="col" className="py-2 pr-3 font-medium">Date</th>
            <th scope="col" className="py-2 pr-3 font-medium">Description</th>
            <th scope="col" className="py-2 pr-3 font-medium">Category</th>
            <th scope="col" className="py-2 pr-3 font-medium">Account</th>
            <th scope="col" className="py-2 pr-3 font-medium">Country</th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">Amount</th>
            <th scope="col" className="py-2 font-medium"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((transaction) => {
            const account = accounts.get(transaction.accountId)
            return (
              <tr key={transaction.id} className="border-b border-gray-800">
                <td className="py-2 pr-3 whitespace-nowrap">{formatDate(transaction.transactionDate)}</td>
                <th scope="row" className="py-2 pr-3 font-normal">{transaction.description ?? '—'}</th>
                <td className="py-2 pr-3"><CategoryText transaction={transaction} /></td>
                <td className="py-2 pr-3">{account?.name ?? `#${transaction.accountId}`}</td>
                <td className="py-2 pr-3">{transaction.countryCode}</td>
                <td className="py-2 pr-3 text-right"><Amount transaction={transaction} currency={account?.currency} /></td>
                <td className="py-2"><Actions transaction={transaction} onAction={onAction} /></td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <ul className="flex flex-col gap-3 md:hidden" aria-label="Transactions">
        {transactions.map((transaction) => {
          const account = accounts.get(transaction.accountId)
          return (
            <li key={transaction.id} className="rounded-lg border border-gray-800 bg-gray-900 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="font-medium">{transaction.description ?? '—'}</p>
                  <p className="text-sm text-gray-400">
                    {formatDate(transaction.transactionDate)} · <CategoryText transaction={transaction} /> · {transaction.countryCode}
                  </p>
                  <p className="text-sm text-gray-400">{account?.name ?? `#${transaction.accountId}`}</p>
                </div>
                <Amount transaction={transaction} currency={account?.currency} />
              </div>
              <div className="mt-2">
                <Actions transaction={transaction} onAction={onAction} />
              </div>
            </li>
          )
        })}
      </ul>
    </>
  )
}
