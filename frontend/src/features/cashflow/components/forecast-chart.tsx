import { formatDate } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import type { Decimal } from '@/shared/money/decimal'
import type { ForecastDto } from '@/shared/types/api'
import { LineChart } from '@/shared/ui/line-chart'

export interface ForecastChartProps {
  forecast: ForecastDto
  currency: string | undefined
}

/** Projected balance per day as a line (D-3); axis and table use the same exact formatter (C-2). */
export function ForecastChart({ forecast, currency }: ForecastChartProps) {
  const points = forecast.days.map((day) => ({ key: day.date, label: formatDate(day.date), value: day.projectedBalance }))
  return (
    <LineChart
      title={`Projected balance, next ${forecast.horizonDays} days`}
      points={points}
      labelHeading="Date"
      valueHeading="Projected balance"
      formatValue={(value: Decimal) => formatMoney(value, currency)}
    />
  )
}
