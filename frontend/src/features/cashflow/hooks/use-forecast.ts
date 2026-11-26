import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QUERY_ROOTS } from '@/shared/api/query-roots'
import type { ForecastHorizon } from '@/shared/types/api'
import { getForecast, refreshForecasts } from '../api/cashflow-api'

export function forecastKey(horizon: ForecastHorizon) {
  return [...QUERY_ROOTS.cashflow, horizon] as const
}

/** One horizon's stored forecast. Disabled while account currencies are unknown, failed or mixed. */
export function useForecast(horizon: ForecastHorizon, enabled: boolean) {
  return useQuery({ queryKey: forecastKey(horizon), queryFn: () => getForecast(horizon), enabled })
}

/**
 * Recalculates all three horizons. The response fills the three forecast queries directly (the
 * dashboard card reads the 30-day one). Never retried: the backend allows one refresh per hour.
 */
export function useRefreshForecasts() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: refreshForecasts,
    retry: false,
    onSuccess: (forecasts) => {
      for (const forecast of forecasts) queryClient.setQueryData(forecastKey(forecast.horizonDays), forecast)
    },
  })
}
