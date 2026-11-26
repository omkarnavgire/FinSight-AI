import { type ReactNode, useState } from 'react'
import { useAccountCurrencies } from '@/features/accounts'
import { formatMonth, todayIsoDate } from '@/shared/format/format-date'
import { isZeroDecimal } from '@/shared/money/decimal'
import { ErrorState } from '@/shared/ui/error-state'
import { MixedCurrencyNotice } from '@/shared/ui/mixed-currency-notice'
import { MonthPicker, type MonthValue } from '@/shared/ui/month-picker'
import { PageLoader } from '@/shared/ui/page-loader'
import { CategoryChart } from '../components/category-chart'
import { QuerySection } from '../components/query-section'
import { TrendChart } from '../components/trend-chart'
import { useCategoryBreakdown, useMonthlyTrend, useTopCategories } from '../hooks/use-analytics'

function monthOf(isoDate: string): MonthValue | undefined {
  const match = /^(\d{4})-(\d{2})-\d{2}$/.exec(isoDate)
  return match ? { year: Number(match[1]), month: Number(match[2]) } : undefined
}

/**
 * Analytics page (T-71). The backend adds spending from every account without converting
 * currencies, so monetary analytics are shown only when all accounts use one currency (D-5).
 * While currencies are loading, failed or mixed, nothing monetary is fetched or shown.
 */
export function AnalyticsPage() {
  const currencies = useAccountCurrencies()
  const mode = currencies.mode
  const safe = currencies.error === null && (mode?.kind === 'single' || mode?.kind === 'none')
  const currency = mode?.kind === 'single' ? mode.currency : undefined
  const [period, setPeriod] = useState<MonthValue | undefined>(undefined)
  const breakdown = useCategoryBreakdown(period, safe)
  const trend = useMonthlyTrend(safe)
  const top = useTopCategories(safe)
  const shown = period ?? (breakdown.data === undefined ? undefined : monthOf(breakdown.data.from)) ?? monthOf(todayIsoDate())

  let content: ReactNode
  if (currencies.error !== null) {
    content = <ErrorState error={currencies.error} title="Could not check account currencies" onRetry={currencies.refetch} />
  } else if (mode === undefined) {
    content = <PageLoader label="Checking account currencies" />
  } else if (mode.kind === 'mixed') {
    content = (
      <>
        <MixedCurrencyNotice currencies={mode.currencies} subject="spending analytics" />
        <p className="text-sm text-gray-300">
          Spending charts and totals are hidden. FinSight adds spending from all your accounts without converting
          currencies, so these numbers would mix {mode.currencies.join(' and ')}.
        </p>
      </>
    )
  } else {
    content = (
      <>
        <div className="flex flex-col gap-2">
          {shown === undefined ? null : (
            <MonthPicker value={shown} onChange={setPeriod} disabled={breakdown.isPending && period === undefined} />
          )}
        </div>
        <QuerySection
          title="Spending by category"
          query={breakdown}
          isEmpty={(data) => data.items.length === 0 || isZeroDecimal(data.total)}
          emptyTitle={shown === undefined ? 'No spending this month' : `No spending in ${formatMonth(shown.year, shown.month)}`}
        >
          {(data) => <CategoryChart title="Spending by category" breakdown={data} currency={currency} />}
        </QuerySection>
        <QuerySection
          title="Monthly trend"
          query={trend}
          isEmpty={(data) => data.months.every((m) => isZeroDecimal(m.spent))}
          emptyTitle="No spending in the last six months"
        >
          {(data) => <TrendChart trend={data} currency={currency} />}
        </QuerySection>
        <QuerySection
          title="Top categories, last 30 days"
          query={top}
          isEmpty={(data) => data.items.length === 0}
          emptyTitle="No spending in the last 30 days"
        >
          {(data) => <CategoryChart title="Top five categories" breakdown={data} currency={currency} />}
        </QuerySection>
      </>
    )
  }

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div>
        <h1 id="page-title" className="text-2xl font-semibold">Analytics</h1>
        <p className="text-sm text-gray-400">Spending only: income is not counted.</p>
      </div>
      {content}
    </section>
  )
}
