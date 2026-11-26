import type { ReactNode } from 'react'
import { useAccountCurrencies } from '@/features/accounts'
import { ErrorState } from '@/shared/ui/error-state'
import { CardLoading, CardNote } from './summary-card'

export interface CurrencyGate {
  /** True only when every counted account uses one currency, or there are none. */
  safe: boolean
  /** The single currency code, when there is one. */
  currency: string | undefined
  /** What to show instead of a backend sum when it is not safe; `null` when safe. */
  blocked: ReactNode
}

/**
 * Decides whether a backend sum over all accounts may be shown (D-5). Spending, budget progress and
 * the forecast add amounts without converting currencies, so they are shown only for one currency.
 *
 * @param activeOnly count active accounts only (the forecast uses active balances)
 */
export function useCurrencyGate(activeOnly = false): CurrencyGate {
  const currencies = useAccountCurrencies(activeOnly)
  const mode = currencies.mode
  if (currencies.error !== null) {
    return {
      safe: false,
      currency: undefined,
      blocked: <ErrorState error={currencies.error} title="Could not check account currencies" onRetry={currencies.refetch} />,
    }
  }
  if (mode === undefined) return { safe: false, currency: undefined, blocked: <CardLoading label="account currencies" /> }
  if (mode.kind === 'mixed') {
    return {
      safe: false,
      currency: undefined,
      blocked: <CardNote>Hidden: your accounts use {mode.currencies.join(' and ')}, and FinSight does not convert currencies.</CardNote>,
    }
  }
  return { safe: true, currency: mode.kind === 'single' ? mode.currency : undefined, blocked: null }
}
