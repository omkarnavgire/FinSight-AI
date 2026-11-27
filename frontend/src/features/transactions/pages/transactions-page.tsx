import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAllAccounts } from '@/features/accounts'
import { toApiError } from '@/shared/api/api-error'
import { todayIsoDate } from '@/shared/format/format-date'
import type { TransactionDto, TransactionFilter } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { ConfirmDialog } from '@/shared/ui/confirm-dialog'
import { Dialog } from '@/shared/ui/dialog'
import { EmptyState } from '@/shared/ui/empty-state'
import { ErrorState } from '@/shared/ui/error-state'
import { PageLoader } from '@/shared/ui/page-loader'
import { Pager } from '@/shared/ui/pager'
import { CategoryForm } from '../components/category-form'
import { TransactionFilters } from '../components/transaction-filters'
import { type TransactionAction, TransactionTable } from '../components/transaction-table'
import { TransactionForm } from '../components/transaction-form'
import { useDeleteTransaction } from '../hooks/use-transaction-mutations'
import { useTransactions } from '../hooks/use-transactions'
import { hasFilters, readFilterParams, writeFilterParams } from '../lib/filter-params'

type Open = { kind: 'create' } | { kind: TransactionAction; transaction: TransactionDto } | null

/** Transactions page (T-69): URL filters, paged list, add, edit, recategorise, delete. No totals are shown. */
export function TransactionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { filter, page } = useMemo(() => readFilterParams(searchParams), [searchParams])
  const [open, setOpen] = useState<Open>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const transactions = useTransactions(filter, page)
  const accounts = useAllAccounts()
  const remove = useDeleteTransaction()

  const accountsById = useMemo(() => new Map((accounts.data ?? []).map((account) => [account.id, account])), [accounts.data])
  const activeAccounts = useMemo(() => (accounts.data ?? []).filter((account) => account.active), [accounts.data])
  const go = (next: { filter?: TransactionFilter; page?: number }) =>
    setSearchParams(writeFilterParams({ filter: next.filter ?? filter, page: next.page ?? 0 }))
  const close = () => setOpen(null)
  const done = (message: string) => {
    setOpen(null)
    setNotice(message)
  }

  const canAdd = activeAccounts.length > 0
  const addButton = (
    <Button onClick={() => setOpen({ kind: 'create' })} disabled={!canAdd}>
      Add transaction
    </Button>
  )

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 id="page-title" className="text-2xl font-semibold">Transactions</h1>
        {addButton}
      </div>
      {accounts.isSuccess && !canAdd ? <Alert tone="info">Add an active account before adding transactions.</Alert> : null}
      {accounts.isError ? <ErrorState error={accounts.error} title="Could not load accounts" onRetry={() => void accounts.refetch()} /> : null}
      {notice === null ? null : <Alert tone="success">{notice}</Alert>}

      <TransactionFilters key={searchParams.toString()} value={filter} accounts={accounts.data ?? []} onApply={(next) => go({ filter: next })} />

      {transactions.isPending ? (
        <PageLoader label="Loading transactions" />
      ) : transactions.isError ? (
        <ErrorState error={transactions.error} title="Could not load transactions" onRetry={() => void transactions.refetch()} />
      ) : transactions.data.totalElements === 0 ? (
        hasFilters(filter) ? (
          <EmptyState title="No matching transactions" message="Try other filters, or clear them." />
        ) : (
          <EmptyState title="No transactions yet" message="Add income or spending to see it here." action={canAdd ? addButton : undefined} />
        )
      ) : (
        <div className="flex flex-col gap-4" aria-busy={transactions.isFetching ? true : undefined}>
          <TransactionTable
            transactions={transactions.data.content}
            accounts={accountsById}
            onAction={(kind, transaction) => {
              remove.reset()
              setOpen({ kind, transaction })
            }}
          />
          <Pager page={transactions.data.page} totalPages={transactions.data.totalPages} onPageChange={(next) => go({ page: next })} disabled={transactions.isFetching} />
        </div>
      )}

      <Dialog open={open?.kind === 'create' || open?.kind === 'edit'} title={open?.kind === 'edit' ? 'Edit transaction' : 'Add transaction'} onClose={close}>
        {open?.kind === 'create' || open?.kind === 'edit' ? (
          <TransactionForm
            accounts={activeAccounts}
            editing={open.kind === 'edit' ? open.transaction : undefined}
            today={todayIsoDate()}
            onCancel={close}
            onSaved={() => done(open.kind === 'edit' ? 'Transaction updated.' : 'Transaction added.')}
          />
        ) : null}
      </Dialog>

      <Dialog open={open?.kind === 'category'} title="Change category" onClose={close}>
        {open?.kind === 'category' ? <CategoryForm transaction={open.transaction} onCancel={close} onSaved={() => done('Category changed.')} /> : null}
      </Dialog>

      <ConfirmDialog
        open={open?.kind === 'delete'}
        title="Delete transaction?"
        confirmLabel="Delete"
        danger
        busy={remove.isPending}
        onCancel={close}
        onConfirm={() => {
          if (open?.kind === 'delete') remove.mutate(open.transaction.id, { onSuccess: () => done('Transaction deleted.') })
        }}
      >
        <p>The account balance will be adjusted. This cannot be undone.</p>
        {remove.error === null ? null : (
          <div className="mt-3">
            <Alert tone="error">{toApiError(remove.error).message}</Alert>
          </div>
        )}
      </ConfirmDialog>
    </section>
  )
}
