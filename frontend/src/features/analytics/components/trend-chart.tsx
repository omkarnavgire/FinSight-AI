import { formatMonth } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import type { MonthlyTrendDto } from '@/shared/types/api'
import { BarChart } from '@/shared/ui/bar-chart'

export interface TrendChartProps {
  trend: MonthlyTrendDto
  currency: string | undefined
}

const SHORT_MONTH = new Intl.DateTimeFormat('en-GB', { month: 'short', timeZone: 'UTC' })

/**
 * Six months of spending, oldest first, as vertical bars with the exact amount on each bar (C-3).
 * Months are separate totals, so bars fit better than a line.
 */
export function TrendChart({ trend, currency }: TrendChartProps) {
  const data = trend.months.map((m) => ({
    key: `${m.year}-${m.month}`,
    label: SHORT_MONTH.format(Date.UTC(m.year, m.month - 1, 1)),
    value: m.spent,
    valueText: formatMoney(m.spent, currency),
  }))
  const first = trend.months[0]
  const last = trend.months[trend.months.length - 1]
  const title = first === undefined || last === undefined
    ? 'Monthly spending'
    : `Monthly spending, ${formatMonth(first.year, first.month)} to ${formatMonth(last.year, last.month)}`
  return <BarChart title={title} orientation="vertical" data={data} labelHeading="Month" valueHeading="Spent" />
}
