/**
 * Route table (T-66, T-67, B-9).
 *
 *   /login, /register     PublicOnlyRoute  (signed-in users are sent on)
 *   /, /dashboard, ...    ProtectedRoute -> AppLayout
 *   *                     NotFoundPage
 * Module paths match `NAV_ITEMS`; `app.test.tsx` checks every sidebar link opens its page.
 */
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router'
import { AccountsPage } from '@/features/accounts/pages/accounts-page'
import { AnalyticsPage } from '@/features/analytics/pages/analytics-page'
import { LoginPage } from '@/features/auth/pages/login-page'
import { RegisterPage } from '@/features/auth/pages/register-page'
import { ProtectedRoute } from '@/features/auth/routes/protected-route'
import { PublicOnlyRoute } from '@/features/auth/routes/public-only-route'
import { BudgetsPage } from '@/features/budgeting/pages/budgets-page'
import { CashflowPage } from '@/features/cashflow/pages/cashflow-page'
import { FraudAlertsPage } from '@/features/fraud/pages/fraud-alerts-page'
import { TransactionsPage } from '@/features/transactions/pages/transactions-page'
import { DashboardPage } from './dashboard/dashboard-page'
import { AppLayout } from './layout/app-layout'
import { NotFoundPage } from './pages/not-found-page'
import { RouteErrorPage } from './pages/route-error-page'

export const appRoutes: RouteObject[] = [
  {
    errorElement: <RouteErrorPage />,
    children: [
      {
        element: <PublicOnlyRoute />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/register', element: <RegisterPage /> },
        ],
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { path: '/', element: <Navigate to="/dashboard" replace /> },
              { path: '/dashboard', element: <DashboardPage /> },
              { path: '/accounts', element: <AccountsPage /> },
              { path: '/transactions', element: <TransactionsPage /> },
              { path: '/budgets', element: <BudgetsPage /> },
              { path: '/analytics', element: <AnalyticsPage /> },
              { path: '/cashflow', element: <CashflowPage /> },
              { path: '/fraud-alerts', element: <FraudAlertsPage /> },
            ],
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

/** The browser router used by the app. */
export function createAppRouter() {
  return createBrowserRouter(appRoutes)
}
