import type { UseQueryResult } from '@tanstack/react-query'
import { CATEGORY_LABELS } from '@/shared/domain/categories'
import { formatMoney } from '@/shared/format/format-money'
import { isNegativeDecimal, negateDecimal } from '@/shared/money/decimal'
import type { CurrencyMode } from '@/shared/money/group-by-currency'
import type { BudgetDto, BudgetProgressDto } from '@/shared/types/api'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import { ErrorState } from '@/shared/ui/error-state'
import { ProgressBar } from '@/shared/ui/progress-bar'
import { Spinner } from '@/shared/ui/spinner'

export interface BudgetCardProps {
  budget: BudgetDto
  progress: UseQueryResult<BudgetProgressDto>
  /** `undefined` while the account currencies are unknown. */
  currencyMode: CurrencyMode | undefined
  /** The account currencies could not be loaded, so amounts cannot be labelled safely. */
  currencyError?: boolean
  onEdit: () => void
  onDelete: () => void
}

function Progress({ progress, currency }: { progress: BudgetProgressDto; currency: string | undefined }) {
  const over = isNegativeDecimal(progress.remaining)
  const label = CATEGORY_LABELS[progress.category]
  return (
    <div className="flex flex-col gap-2">
      <ProgressBar label={`${label} budget`} percent={progress.percentUsed} />
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        <dt className="text-gray-400">Spent</dt>
        <dd className="text-right tabular-nums">{formatMoney(progress.spent, currency)}</dd>
        <dt className="text-gray-400">{over ? 'Over by' : 'Left'}</dt>
        <dd className={`text-right tabular-nums ${over ? 'text-red-300' : ''}`}>
          {formatMoney(over ? negateDecimal(progress.remaining) : progress.remaining, currency)}
        </dd>
      </dl>
      {progress.alertsFired.length === 0 ? null : (
        <p className="flex flex-wrap gap-2">
          {progress.alertsFired.map((level) => (
            <Badge key={level} tone={level >= 100 ? 'danger' : 'warning'}>{level}% alert sent</Badge>
          ))}
        </p>
      )}
    </div>
  )
}

/**
 * One budget with its progress. Spent and progress are backend sums over every account, so they
 * are shown only when all accounts share one currency (D-5); otherwise the page-level notice
 * explains why they are held back.
 */
export function BudgetCard({ budget, progress, currencyMode, currencyError = false, onEdit, onDelete }: BudgetCardProps) {
  const label = CATEGORY_LABELS[budget.category]
  const currency = currencyMode?.kind === 'single' ? currencyMode.currency : undefined
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">{label}</h2>
          <p className="text-sm text-gray-400">
            Limit <span className="tabular-nums">{formatMoney(budget.monthlyLimit, currency)}</span>
          </p>
        </div>
        <div className="flex gap-1">
          <Button variant="ghost" onClick={onEdit} ariaLabel={`Edit ${label} budget`}>Edit</Button>
          <Button variant="ghost" onClick={onDelete} ariaLabel={`Delete ${label} budget`}>Delete</Button>
        </div>
      </div>
      {currencyError ? (
        <p className="text-sm text-gray-400">Progress is hidden until your account currencies load.</p>
      ) : currencyMode === undefined || progress.isPending ? (
        <p role="status" className="flex items-center gap-2 text-sm text-gray-300"><Spinner /> Loading progress…</p>
      ) : currencyMode.kind === 'mixed' ? (
        <p className="text-sm text-gray-400">Progress is not shown because your accounts use several currencies.</p>
      ) : progress.isError ? (
        <ErrorState error={progress.error} title={`Could not load ${label} progress`} onRetry={() => void progress.refetch()} />
      ) : (
        <Progress progress={progress.data} currency={currency} />
      )}
    </li>
  )
}
