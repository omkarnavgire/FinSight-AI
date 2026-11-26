/** Main navigation (T-67). One entry per MVP module; order matches the sidebar. Paths match `router.tsx`. */
export interface NavItem {
  path: string
  label: string
}

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/dashboard', label: 'Dashboard' },
  { path: '/accounts', label: 'Accounts' },
  { path: '/transactions', label: 'Transactions' },
  { path: '/budgets', label: 'Budgets' },
  { path: '/analytics', label: 'Analytics' },
  { path: '/cashflow', label: 'Cash Flow' },
  { path: '/fraud-alerts', label: 'Fraud Alerts' },
]
