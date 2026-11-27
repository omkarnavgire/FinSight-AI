import { formatDate, formatDateTime } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import { isNegativeDecimal, subtractDecimal } from '@/shared/money/decimal'
import type { ForecastDto } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'

export interface ForecastSummaryProps {
  forecast: ForecastDto
  currency: string | undefined
}

/** Start and end balances, their exact difference, and the confidence warnings. */
export function ForecastSummary({ forecast, currency }: ForecastSummaryProps) {
  const change = subtractDecimal(forecast.projectedEndBalance, forecast.startingBalance)
  const sign = isNegativeDecimal(change) ? '' : '+'
  return (
    <div className="flex flex-col gap-3">
      {forecast.reason === 'NO_HISTORY' ? (
        <Alert tone="info" title="No history yet" live={false}>
          There are no transactions yet, so the forecast is flat: the balance stays the same every day.
        </Alert>
      ) : forecast.lowConfidence ? (
        <Alert tone="warning" title="Low confidence" live={false}>
          There is less than 30 days of history, so this forecast may be far off.
        </Alert>
      ) : null}
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="rounded-lg border border-gray-800 bg-gray-900 p-4">
          <dt className="text-sm text-gray-400">Balance on {formatDate(forecast.asOf)}</dt>
          <dd className="mt-1 text-xl font-semibold tabular-nums">{formatMoney(forecast.startingBalance, currency)}</dd>
        </div>
        <div className="rounded-lg border border-gray-800 bg-gray-900 p-4">
          <dt className="text-sm text-gray-400">Projected on {formatDate(forecast.to)}</dt>
          <dd className="mt-1 text-xl font-semibold tabular-nums">{formatMoney(forecast.projectedEndBalance, currency)}</dd>
        </div>
        <div className="rounded-lg border border-gray-800 bg-gray-900 p-4">
          <dt className="text-sm text-gray-400">Change over {forecast.horizonDays} days</dt>
          <dd className={`mt-1 text-xl font-semibold tabular-nums ${isNegativeDecimal(change) ? 'text-red-300' : 'text-emerald-300'}`}>
            {sign}
            {formatMoney(change, currency)}
          </dd>
        </div>
      </dl>
      <p className="text-xs text-gray-400">Calculated {formatDateTime(forecast.generatedAt)}. Forecasts update nightly or when you recalculate.</p>
    </div>
  )
}
