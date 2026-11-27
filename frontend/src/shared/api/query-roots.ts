/**
 * Root React Query keys, one per module (plan section 3). Invalidating a root refreshes every
 * query under it. Modules build their own keys on these roots. The dashboard has no root of its
 * own: its cards reuse the module queries, so module invalidation refreshes them too.
 */
export const QUERY_ROOTS = {
  accounts: ['accounts'],
  transactions: ['transactions'],
  budgets: ['budgets'],
  budgetProgress: ['budget-progress'],
  analytics: ['analytics'],
  cashflow: ['cashflow'],
  fraudAlerts: ['fraud-alerts'],
} as const satisfies Record<string, readonly [string]>
