import { type ReactNode, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAccountCurrencies } from '@/features/accounts'
import { toApiError } from '@/shared/api/api-error'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { ErrorState } from '@/shared/ui/error-state'
import { MixedCurrencyNotice } from '@/shared/ui/mixed-currency-notice'
import { PageLoader } from '@/shared/ui/page-loader'
import { SegmentedControl } from '@/shared/ui/segmented-control'
import { ForecastChart } from '../components/forecast-chart'
import { ForecastSummary } from '../components/forecast-summary'
import { useForecast, useRefreshForecasts } from '../hooks/use-forecast'
import { readHorizonParam, refreshLimitMessage } from '../lib/horizon'

const HORIZON_OPTIONS = [
  { value: '30', label: '30 days' },
  { value: '60', label: '60 days' },
  { value: '90', label: '90 days' },
] as const

/**
 * Cash-flow page (T-72). The forecast starts from the sum of active account balances without
 * currency conversion, so it is shown only when all active accounts share one currency (D-5).
 */
export function CashflowPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const horizon = readHorizonParam(searchParams)
  const currencies = useAccountCurrencies(true)
  const mode = currencies.mode
  const safe = currencies.error === null && (mode?.kind === 'single' || mode?.kind === 'none')
  const currency = mode?.kind === 'single' ? mode.currency : undefined
  const forecast = useForecast(horizon, safe)
  const refresh = useRefreshForecasts()
  const [refreshed, setRefreshed] = useState(false)

  const refreshError = refresh.error === null ? null : toApiError(refresh.error)
  const refreshMessage =
    refreshError === null ? null : refreshError.status === 429 ? refreshLimitMessage(refreshError.retryAfterSeconds) : refreshError.message

  let content: ReactNode
  if (currencies.error !== null) {
    content = <ErrorState error={currencies.error} title="Could not check account currencies" onRetry={currencies.refetch} />
  } else if (mode === undefined) {
    content = <PageLoader label="Checking account currencies" />
  } else if (mode.kind === 'mixed') {
    content = (
      <>
        <MixedCurrencyNotice currencies={mode.currencies} subject="the forecast" />
        <p className="text-sm text-gray-300">
          The forecast is hidden. It starts from the sum of your active balances without converting currencies, so it
          would mix {mode.currencies.join(' and ')}.
        </p>
      </>
    )
  } else if (forecast.isPending) {
    content = <PageLoader label="Loading forecast" />
  } else if (forecast.isError) {
    content = <ErrorState error={forecast.error} title="Could not load the forecast" onRetry={() => void forecast.refetch()} />
  } else {
    content = (
      <div className="flex flex-col gap-6" aria-busy={forecast.isFetching ? true : undefined}>
        <ForecastSummary forecast={forecast.data} currency={currency} />
        <ForecastChart forecast={forecast.data} currency={currency} />
      </div>
    )
  }

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 id="page-title" className="text-2xl font-semibold">Cash Flow</h1>
          <p className="text-sm text-gray-400">Forecast from your recurring income and spending.</p>
        </div>
        {safe ? (
          <Button
            variant="secondary"
            busy={refresh.isPending}
            onClick={() => {
              setRefreshed(false)
              refresh.mutate(undefined, { onSuccess: () => setRefreshed(true) })
            }}
          >
            Recalculate
          </Button>
        ) : null}
      </div>
      <SegmentedControl<string>
        legend="Forecast length"
        options={HORIZON_OPTIONS}
        value={String(horizon)}
        onChange={(value) => setSearchParams(value === '30' ? {} : { horizon: value })}
      />
      {refreshed ? <Alert tone="success">Forecast recalculated.</Alert> : null}
      {refreshMessage === null ? null : <Alert tone="error">{refreshMessage}</Alert>}
      {content}
    </section>
  )
}
