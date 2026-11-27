import { FRAUD_RULE_LABELS, SEVERITY_LABELS } from '@/shared/domain/categories'
import { formatDateTime } from '@/shared/format/format-date'
import type { FraudAlertDto, Severity } from '@/shared/types/api'
import { Badge, type BadgeTone } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'

const SEVERITY_TONE: Readonly<Record<Severity, BadgeTone>> = { LOW: 'info', MEDIUM: 'warning', HIGH: 'danger' }

export interface FraudAlertListProps {
  alerts: readonly FraudAlertDto[]
  /** The alert being acknowledged, if any. */
  busyId: number | null
  onAcknowledge: (alert: FraudAlertDto) => void
}

/** Open alerts as cards. Severity is written out, not shown by colour alone. Text is plain (React escapes it). */
export function FraudAlertList({ alerts, busyId, onAcknowledge }: FraudAlertListProps) {
  return (
    <ul className="flex flex-col gap-3" aria-label="Open fraud alerts">
      {alerts.map((alert) => {
        const rule = FRAUD_RULE_LABELS[alert.rule]
        return (
          <li key={alert.id} className="flex flex-col gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-base font-semibold">{rule}</h2>
                <Badge tone={SEVERITY_TONE[alert.severity]}>{SEVERITY_LABELS[alert.severity]} severity</Badge>
              </div>
              <p className="text-sm text-gray-200">{alert.description}</p>
              <p className="text-xs text-gray-400">
                {formatDateTime(alert.createdAt)} · Transaction #{alert.transactionId}
              </p>
            </div>
            <Button
              variant="secondary"
              busy={busyId === alert.id}
              disabled={busyId !== null && busyId !== alert.id}
              onClick={() => onAcknowledge(alert)}
              ariaLabel={`Acknowledge ${rule} alert for transaction ${alert.transactionId}`}
            >
              Acknowledge
            </Button>
          </li>
        )
      })}
    </ul>
  )
}
