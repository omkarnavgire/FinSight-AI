import { useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import { FRAUD_RULE_LABELS } from '@/shared/domain/categories'
import { Alert } from '@/shared/ui/alert'
import { EmptyState } from '@/shared/ui/empty-state'
import { ErrorState } from '@/shared/ui/error-state'
import { PageLoader } from '@/shared/ui/page-loader'
import { Pager } from '@/shared/ui/pager'
import { FraudAlertList } from '../components/fraud-alert-list'
import { useAcknowledgeAlert, useFraudAlerts } from '../hooks/use-fraud-alerts'

/** Fraud alerts page (T-73): open alerts, newest first, each with Acknowledge. */
export function FraudAlertsPage() {
  const [page, setPage] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const alerts = useFraudAlerts(page)
  const acknowledge = useAcknowledgeAlert()
  const busyId = acknowledge.isPending ? (acknowledge.variables ?? null) : null

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div>
        <h1 id="page-title" className="text-2xl font-semibold">Fraud Alerts</h1>
        <p className="text-sm text-gray-400">Unusual activity found in your transactions. Acknowledge an alert once you have checked it.</p>
      </div>

      {notice === null ? null : <Alert tone="success">{notice}</Alert>}
      {acknowledge.error === null ? null : <Alert tone="error">{toApiError(acknowledge.error).message}</Alert>}

      {alerts.isPending ? (
        <PageLoader label="Loading fraud alerts" />
      ) : alerts.isError ? (
        <ErrorState error={alerts.error} title="Could not load fraud alerts" onRetry={() => void alerts.refetch()} />
      ) : alerts.data.totalElements === 0 ? (
        <EmptyState title="No open alerts" message="Nothing needs your attention right now." />
      ) : (
        <div className="flex flex-col gap-4" aria-busy={alerts.isFetching ? true : undefined}>
          <FraudAlertList
            alerts={alerts.data.content}
            busyId={busyId}
            onAcknowledge={(alert) => {
              setNotice(null)
              acknowledge.mutate(alert.id, {
                onSuccess: () => {
                  setNotice(`${FRAUD_RULE_LABELS[alert.rule]} alert acknowledged.`)
                  if (alerts.data.content.length === 1 && page > 0) setPage(page - 1)
                },
              })
            }}
          />
          <Pager page={alerts.data.page} totalPages={alerts.data.totalPages} onPageChange={setPage} disabled={alerts.isFetching} />
        </div>
      )}
    </section>
  )
}
