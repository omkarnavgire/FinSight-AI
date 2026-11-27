import { useQuery } from '@tanstack/react-query'
import { QUERY_ROOTS } from '@/shared/api/query-roots'
import { getCategoryBreakdown, getMonthlyTrend, getTopCategories, type Period } from '../api/analytics-api'

export const analyticsKeys = {
  breakdown: (period: Period | undefined) => [...QUERY_ROOTS.analytics, 'breakdown', period?.year ?? null, period?.month ?? null] as const,
  trend: [...QUERY_ROOTS.analytics, 'trend'] as const,
  top: [...QUERY_ROOTS.analytics, 'top'] as const,
}

/**
 * The three analytics queries. `enabled` is false while account currencies are unknown, failed or
 * mixed, so no misleading cross-currency sum is even fetched.
 */
export function useCategoryBreakdown(period: Period | undefined, enabled: boolean) {
  return useQuery({ queryKey: analyticsKeys.breakdown(period), queryFn: () => getCategoryBreakdown(period), enabled })
}

export function useMonthlyTrend(enabled: boolean) {
  return useQuery({ queryKey: analyticsKeys.trend, queryFn: getMonthlyTrend, enabled })
}

export function useTopCategories(enabled: boolean) {
  return useQuery({ queryKey: analyticsKeys.top, queryFn: () => getTopCategories(), enabled })
}
