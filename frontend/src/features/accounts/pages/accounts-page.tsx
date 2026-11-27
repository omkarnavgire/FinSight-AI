import { useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import type { AccountDto } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { ConfirmDialog } from '@/shared/ui/confirm-dialog'
import { Dialog } from '@/shared/ui/dialog'
import { EmptyState } from '@/shared/ui/empty-state'
import { ErrorState } from '@/shared/ui/error-state'
import { PageLoader } from '@/shared/ui/page-loader'
import { Pager } from '@/shared/ui/pager'
import { AccountList } from '../components/account-list'
import { CreateAccountForm } from '../components/create-account-form'
import { CurrencyTotals } from '../components/currency-totals'
import { useDeactivateAccount } from '../hooks/use-account-mutations'
import { useAccounts } from '../hooks/use-accounts'

/** Accounts page (T-68): per-currency totals, paged list, create and deactivate. */
export function AccountsPage() {
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [toDeactivate, setToDeactivate] = useState<AccountDto | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const accounts = useAccounts(page)
  const deactivate = useDeactivateAccount()

  const newAccountButton = <Button onClick={() => setCreating(true)}>New account</Button>

  function confirmDeactivate(): void {
    if (toDeactivate === null) return
    const target = toDeactivate
    deactivate.mutate(target.id, {
      onSuccess: () => {
        setToDeactivate(null)
        setNotice(`${target.name} is now inactive.`)
      },
    })
  }

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 id="page-title" className="text-2xl font-semibold">
          Accounts
        </h1>
        {newAccountButton}
      </div>

      {notice === null ? null : <Alert tone="success">{notice}</Alert>}

      <CurrencyTotals />

      {accounts.isPending ? (
        <PageLoader label="Loading accounts" />
      ) : accounts.isError ? (
        <ErrorState error={accounts.error} title="Could not load accounts" onRetry={() => void accounts.refetch()} />
      ) : accounts.data.totalElements === 0 ? (
        <EmptyState title="No accounts yet" message="Add an account to start tracking your money." action={newAccountButton} />
      ) : (
        <div className="flex flex-col gap-4" aria-busy={accounts.isFetching ? true : undefined}>
          <AccountList
            accounts={accounts.data.content}
            onDeactivate={(account) => {
              deactivate.reset()
              setToDeactivate(account)
            }}
          />
          <Pager page={accounts.data.page} totalPages={accounts.data.totalPages} onPageChange={setPage} disabled={accounts.isFetching} />
        </div>
      )}

      <Dialog open={creating} title="New account" onClose={() => setCreating(false)}>
        <CreateAccountForm
          onCancel={() => setCreating(false)}
          onCreated={(account) => {
            setCreating(false)
            setNotice(`${account.name} was created.`)
          }}
        />
      </Dialog>

      <ConfirmDialog
        open={toDeactivate !== null}
        title="Deactivate account?"
        confirmLabel="Deactivate"
        danger
        busy={deactivate.isPending}
        onConfirm={confirmDeactivate}
        onCancel={() => setToDeactivate(null)}
      >
        <p>
          {toDeactivate?.name} will be kept with its history, but you cannot add new transactions to it.
        </p>
        {deactivate.error === null ? null : (
          <div className="mt-3">
            <Alert tone="error">{toApiError(deactivate.error).message}</Alert>
          </div>
        )}
      </ConfirmDialog>
    </section>
  )
}
