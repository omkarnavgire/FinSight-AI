import { useAccountCurrencies } from '@/features/accounts'
import { useAuth } from '@/features/auth/hooks/use-auth'
import { MixedCurrencyNotice } from '@/shared/ui/mixed-currency-notice'
import { BalanceCard } from './balance-card'
import { BudgetsCard } from './budgets-card'
import { ForecastCard } from './forecast-card'
import { FraudCard } from './fraud-card'
import { RecentTransactionsCard } from './recent-transactions-card'
import { SpendingCard } from './spending-card'

/**
 * Dashboard (T-67, B-8): six cards that load and fail on their own. Each card reuses its module's
 * query, so data already loaded elsewhere is shown from the cache.
 */
export function DashboardPage() {
  const { user } = useAuth()
  const currencies = useAccountCurrencies()
  const mode = currencies.mode

  return (
    <section aria-labelledby="page-title" className="flex flex-col gap-6">
      <div>
        <h1 id="page-title" className="text-2xl font-semibold">Dashboard</h1>
        {user === null ? null : <p className="mt-1 text-gray-300">Signed in as {user.email}</p>}
      </div>
      {mode?.kind === 'mixed' ? (
        <MixedCurrencyNotice currencies={mode.currencies} subject="spending, budget progress and the forecast" />
      ) : null}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <BalanceCard />
        <SpendingCard />
        <BudgetsCard />
        <ForecastCard />
        <FraudCard />
        <RecentTransactionsCard />
      </div>
    </section>
  )
}
