import { CATEGORY_LABELS } from '@/shared/domain/categories'
import { formatDate } from '@/shared/format/format-date'
import { formatMoney, formatPercent } from '@/shared/format/format-money'
import type { CategoryBreakdownDto } from '@/shared/types/api'
import { BarChart } from '@/shared/ui/bar-chart'

export interface CategoryChartProps {
  title: string
  breakdown: CategoryBreakdownDto
  /** The single account currency, or `undefined` when there are no accounts. */
  currency: string | undefined
}

/**
 * Spending per category with exact amounts and the backend's percentage of the total. Used for the
 * month breakdown and the 30-day top five. Only rendered when all accounts share one currency.
 */
export function CategoryChart({ title, breakdown, currency }: CategoryChartProps) {
  const data = breakdown.items.map((item) => ({
    key: item.category,
    label: CATEGORY_LABELS[item.category],
    value: item.amount,
    valueText: `${formatMoney(item.amount, currency)} (${formatPercent(item.percent)})`,
  }))
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-gray-300">
        {formatDate(breakdown.from)} to {formatDate(breakdown.to)} · Total spending{' '}
        <span className="font-semibold tabular-nums text-gray-100">{formatMoney(breakdown.total, currency)}</span>
      </p>
      <BarChart title={title} data={data} labelHeading="Category" valueHeading="Amount (share)" />
    </div>
  )
}
