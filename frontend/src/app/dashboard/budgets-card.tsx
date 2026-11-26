import { useBudgetProgress, useBudgets } from '@/features/budgeting'
import { CATEGORY_LABELS } from '@/shared/domain/categories'
import { ErrorState } from '@/shared/ui/error-state'
import { ProgressBar } from '@/shared/ui/progress-bar'
import { progressLevel } from '@/shared/ui/progress-level'
import { useCurrencyGate } from './currency-gate'
import { CardLoading, CardNote, QueryBody, SummaryCard } from './summary-card'

const SHOWN = 3

/** This month's budgets: how many are near or over the limit, plus the first few bars. */
export function BudgetsCard() {
  const gate = useCurrencyGate()
  const budgets = useBudgets(0)
  // Progress is a sum over every account; it is not even requested unless one currency is in use.
  const progress = useBudgetProgress(gate.safe ? (budgets.data?.content ?? []) : [])

  return (
    <SummaryCard title="Budgets" to="/budgets" linkLabel="Open budgets">
      <QueryBody
        query={budgets}
        label="budgets"
        isEmpty={(data) => data.totalElements === 0}
        empty={<CardNote>No budgets this month.</CardNote>}
      >
        {(data) => {
          const count = `${data.totalElements} budget${data.totalElements === 1 ? '' : 's'} this month`
          if (gate.blocked !== null) {
            return (
              <>
                <p className="text-sm text-gray-300">{count}</p>
                {gate.blocked}
              </>
            )
          }
          if (progress.some((result) => result.isPending)) return <CardLoading label="budget progress" />
          const failed = progress.filter((result) => result.isError)
          if (failed.length > 0) {
            return (
              <ErrorState
                error={failed[0]?.error}
                title="Could not load budget progress"
                onRetry={() => failed.forEach((result) => void result.refetch())}
              />
            )
          }
          const loaded = progress.flatMap((result) => (result.data === undefined ? [] : [result.data]))
          const levels = loaded.map((item) => progressLevel(item.percentUsed))
          const near = levels.filter((level) => level === 'near').length
          const over = levels.filter((level) => level === 'over').length
          return (
            <>
              <p className="text-sm text-gray-300">
                {count}: {near} near the limit, {over} over the limit
              </p>
              <ul className="flex flex-col gap-3">
                {loaded.slice(0, SHOWN).map((item) => (
                  <li key={item.budgetId}>
                    <p className="text-sm">{CATEGORY_LABELS[item.category]}</p>
                    <ProgressBar label={`${CATEGORY_LABELS[item.category]} budget`} percent={item.percentUsed} />
                  </li>
                ))}
              </ul>
            </>
          )
        }}
      </QueryBody>
    </SummaryCard>
  )
}
