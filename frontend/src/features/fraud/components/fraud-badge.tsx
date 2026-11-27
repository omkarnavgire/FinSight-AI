import { Badge } from '@/shared/ui/badge'
import { useFraudAlertCount } from '../hooks/use-fraud-alerts'

/**
 * Unacknowledged fraud-alert count for the navigation (T-73). Hidden while loading, on error and at
 * zero, so it never shows a wrong number. Screen readers hear the full sentence.
 */
export function FraudBadge() {
  const count = useFraudAlertCount()
  if (count.data === undefined || count.data === 0) return null
  const label = `${count.data} unacknowledged fraud alert${count.data === 1 ? '' : 's'}`
  return (
    <Badge tone="danger" srLabel={label}>
      {count.data > 99 ? '99+' : count.data}
    </Badge>
  )
}
