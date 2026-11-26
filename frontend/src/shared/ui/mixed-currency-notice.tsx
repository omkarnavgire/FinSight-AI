import { Alert } from './alert'

export interface MixedCurrencyNoticeProps {
  /** The distinct currency codes involved, e.g. ["EUR", "USD"]. */
  currencies: readonly string[]
  /** What is held back, e.g. "this month's spending total". */
  subject: string
}

function joinCodes(codes: readonly string[]): string {
  if (codes.length <= 1) return codes.join('')
  return `${codes.slice(0, -1).join(', ')} and ${codes[codes.length - 1] ?? ''}`
}

/**
 * Explains why a combined amount is not shown (D-5). FinSight has no exchange rates, and the
 * backend adds amounts in different currencies as plain numbers, so that sum is not a real
 * amount in any currency. Pages show this notice instead of the misleading total or chart.
 */
export function MixedCurrencyNotice({ currencies, subject }: MixedCurrencyNoticeProps) {
  return (
    <Alert tone="warning" title="Several currencies" live={false}>
      Your accounts use {joinCodes(currencies)}. FinSight does not convert between currencies, so {subject} is not
      shown as one amount. See each account for its own currency.
    </Alert>
  )
}
