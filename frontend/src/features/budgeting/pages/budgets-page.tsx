import { useState } from 'react'
import { useAccountCurrencies } from '@/features/accounts'
import { toApiError } from '@/shared/api/api-error'
import { CATEGORY_LABELS } from '@/shared/domain/categories'
import { formatMonth } from '@/shared/format/format-date'
import type { BudgetDto } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { ConfirmDialog } from '@/shared/ui/confirm-dialog'
import { Dialog } from '@/shared/ui/dialog'
import { EmptyState } from '@/shared/ui/empty-state'
import { ErrorState } from '@/shared/ui/error-state'
import { MixedCurrencyNotice } from '@/shared/ui/mixed-currency-notice'
import { PageLoader } from '@/shared/ui/page-loader'
import { Pager } from '@/shared/ui/pager'
import { BudgetCard } from '../components/budget-card'
import { BudgetForm } from '../components/budget-form'
import { useDeleteBudget } from '../hooks/use-budget-mutations'
import { useBudgetProgress, useBudgets } from '../hooks/use-budgets'

type Open = { kind: 'create' } | { kind: 'edit' | 'delete'; budget: BudgetDto } | null

/** Budgets page (T-70): this month's budgets with progress, create, edit limit, delete. */
export function BudgetsPage() {
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState<Open>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const budgets = useBudgets(page)
  const progress = useBudgetProgress(budgets.data?.content ?? [])
  const currencies = useAccountCurrencies()
  const remove = useDeleteBudget()
  const mode = currencies.mode
  const single = mode?.kind === 'single' ? mode.currency : undefined
  const first = budgets.data?.content[0]
  const close = () => setOpen(null)
  const done = (message: string) => {
    setOpen(null)
    setNotice(message)
  }
  const addButton = <Button onClick={() => setOpen({ kind: 'create' })}>New budget</Button>

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 id="page-title" className="text-2xl font-semibold">Budgets</h1>
          <p className="text-sm text-gray-400">{first === undefined ? 'This month' : formatMonth(first.periodYear, first.periodMonth)}</p>
        </div>
        {addButton}
      </div>

      {notice === null ? null : <Alert tone="success">{notice}</Alert>}
      {mode?.kind === 'mixed' ? <MixedCurrencyNotice currencies={mode.currencies} subject="budget spending" /> : null}
      {currencies.error !== null ? (
        <ErrorState error={currencies.error} title="Could not check account currencies" onRetry={currencies.refetch} />
      ) : null}

      {budgets.isPending ? (
        <PageLoader label="Loading budgets" />
      ) : budgets.isError ? (
        <ErrorState error={budgets.error} title="Could not load budgets" onRetry={() => void budgets.refetch()} />
      ) : budgets.data.totalElements === 0 ? (
        <EmptyState title="No budgets this month" message="Set a monthly limit for a spending category." action={addButton} />
      ) : (
        <div className="flex flex-col gap-4" aria-busy={budgets.isFetching ? true : undefined}>
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {budgets.data.content.map((budget, index) => {
              const result = progress[index]
              if (result === undefined) return null
              return (
                <BudgetCard
                  key={budget.id}
                  budget={budget}
                  progress={result}
                  currencyMode={mode}
                  currencyError={currencies.error !== null}
                  onEdit={() => setOpen({ kind: 'edit', budget })}
                  onDelete={() => {
                    remove.reset()
                    setOpen({ kind: 'delete', budget })
                  }}
                />
              )
            })}
          </ul>
          <Pager page={budgets.data.page} totalPages={budgets.data.totalPages} onPageChange={setPage} disabled={budgets.isFetching} />
        </div>
      )}

      <Dialog open={open?.kind === 'create' || open?.kind === 'edit'} title={open?.kind === 'edit' ? 'Edit budget' : 'New budget'} onClose={close}>
        {open?.kind === 'create' || open?.kind === 'edit' ? (
          <BudgetForm
            editing={open.kind === 'edit' ? open.budget : undefined}
            currency={single}
            onCancel={close}
            onSaved={(budget) => done(`${CATEGORY_LABELS[budget.category]} budget ${open.kind === 'edit' ? 'updated' : 'created'}.`)}
          />
        ) : null}
      </Dialog>

      <ConfirmDialog
        open={open?.kind === 'delete'}
        title="Delete budget?"
        confirmLabel="Delete"
        danger
        busy={remove.isPending}
        onCancel={close}
        onConfirm={() => {
          if (open?.kind === 'delete') {
            const target = open.budget
            remove.mutate(target.id, { onSuccess: () => done(`${CATEGORY_LABELS[target.category]} budget deleted.`) })
          }
        }}
      >
        <p>Your transactions are not changed.</p>
        {remove.error === null ? null : (
          <div className="mt-3">
            <Alert tone="error">{toApiError(remove.error).message}</Alert>
          </div>
        )}
      </ConfirmDialog>
    </section>
  )
}
