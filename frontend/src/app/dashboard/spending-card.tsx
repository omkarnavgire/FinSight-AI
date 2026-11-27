import { useMonthlyTrend } from '@/features/analytics'
import { formatMonth } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import { isZeroDecimal } from '@/shared/money/decimal'
import { useCurrencyGate } from './currency-gate'
import { CardNote, QueryBody, SummaryCard } from './summary-card'

/** This month's spending: the last entry of the six-month trend (same cache as the Analytics page). */
export function SpendingCard() {
  const gate = useCurrencyGate()
  const trend = useMonthlyTrend(gate.safe)
  return (
    <SummaryCard title="This month's spending" to="/analytics" linkLabel="Open analytics">
      {gate.blocked ?? (
        <QueryBody
          query={trend}
          label="spending"
          isEmpty={(data) => {
            const last = data.months[data.months.length - 1]
            return last === undefined || isZeroDecimal(last.spent)
          }}
          empty={<CardNote>No spending this month yet.</CardNote>}
        >
          {(data) => {
            const last = data.months[data.months.length - 1]
            if (last === undefined) return null
            return (
              <>
                <p className="text-sm text-gray-400">{formatMonth(last.year, last.month)}</p>
                <p className="text-xl font-semibold tabular-nums">{formatMoney(last.spent, gate.currency)}</p>
              </>
            )
          }}
        </QueryBody>
      )}
    </SummaryCard>
  )
}
