import { useFraudAlertCount } from '@/features/fraud'
import { QueryBody, SummaryCard } from './summary-card'

/** Unacknowledged fraud-alert count (same query and cache as the sidebar badge). */
export function FraudCard() {
  const count = useFraudAlertCount()
  return (
    <SummaryCard title="Fraud alerts" to="/fraud-alerts" linkLabel="Review fraud alerts">
      <QueryBody query={count} label="fraud alerts">
        {(total) =>
          total === 0 ? (
            <p className="text-sm text-gray-300">No open alerts.</p>
          ) : (
            <p className="text-xl font-semibold text-red-300">
              {total} unacknowledged alert{total === 1 ? '' : 's'}
            </p>
          )
        }
      </QueryBody>
    </SummaryCard>
  )
}
