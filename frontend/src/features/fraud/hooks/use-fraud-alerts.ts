import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QUERY_ROOTS } from '@/shared/api/query-roots'
import { acknowledgeFraudAlert, countFraudAlerts, listFraudAlerts } from '../api/fraud-api'

export const FRAUD_PAGE_SIZE = 20

/** How often the badge rechecks while the tab is visible (D-9). */
export const BADGE_REFRESH_MS = 60_000

export const fraudKeys = {
  list: (page: number) => [...QUERY_ROOTS.fraudAlerts, 'list', page] as const,
  count: [...QUERY_ROOTS.fraudAlerts, 'count'] as const,
}

/** One page of unacknowledged alerts. */
export function useFraudAlerts(page: number) {
  return useQuery({
    queryKey: fraudKeys.list(page),
    queryFn: () => listFraudAlerts(page, FRAUD_PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
}

/**
 * The unacknowledged count for the badge (D-9): refetched on window focus, every 60 s while the
 * tab is visible, and whenever `fraud-alerts` is invalidated (for example after a transaction).
 */
export function useFraudAlertCount() {
  return useQuery({
    queryKey: fraudKeys.count,
    queryFn: countFraudAlerts,
    refetchOnWindowFocus: true,
    refetchInterval: BADGE_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

/** Acknowledges an alert, then refreshes the list and the count (badge and dashboard card). Never retried. */
export function useAcknowledgeAlert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: acknowledgeFraudAlert,
    retry: false,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: QUERY_ROOTS.fraudAlerts }),
  })
}
