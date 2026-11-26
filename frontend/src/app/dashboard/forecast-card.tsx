import { useForecast } from '@/features/cashflow'
import { formatDate } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import { useCurrencyGate } from './currency-gate'
import { QueryBody, SummaryCard } from './summary-card'

/**
 * The stored 30-day forecast (same cache as the Cash Flow page). Read only: it never asks for a
 * recalculation. Checked against active accounts, as the forecast starts from active balances.
 */
export function ForecastCard() {
  const gate = useCurrencyGate(true)
  const forecast = useForecast(30, gate.safe)
  return (
    <SummaryCard title="30-day forecast" to="/cashflow" linkLabel="Open cash flow">
      {gate.blocked ?? (
        <QueryBody query={forecast} label="the forecast">
          {(data) => (
            <>
              <p className="text-sm text-gray-400">Projected on {formatDate(data.to)}</p>
              <p className="text-xl font-semibold tabular-nums">{formatMoney(data.projectedEndBalance, gate.currency)}</p>
              {data.reason === 'NO_HISTORY' ? (
                <p className="text-sm text-sky-200">No transactions yet, so the forecast is flat.</p>
              ) : data.lowConfidence ? (
                <p className="text-sm text-amber-200">Low confidence: less than 30 days of history.</p>
              ) : null}
            </>
          )}
        </QueryBody>
      )}
    </SummaryCard>
  )
}
