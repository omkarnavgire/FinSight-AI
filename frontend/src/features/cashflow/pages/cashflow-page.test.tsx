import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { QueryClient } from '@tanstack/react-query'
import { CashflowPage } from './cashflow-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import type { ForecastDto } from '@/shared/types/api'
import { deferred, type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, json, page } from '@/test/fixtures'
import { dailyPoints, fullForecast } from '@/test/forecast-fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type AccountRow = NonNullable<Parameters<typeof account>[0]>

interface Options {
  accounts?: AccountRow[] | 'error'
  accountsGate?: Promise<void>
  forecast?: (horizon: ForecastDto['horizonDays']) => MockReply | null
  refresh?: () => MockReply
}

function server(options: Options = {}) {
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter(async (config): Promise<MockReply> => {
    calls.push(config)
    if (config.url === '/accounts') {
      if (options.accountsGate) await options.accountsGate
      if (options.accounts === 'error') return { status: 500, body: '{"status":500}' }
      const rows = options.accounts ?? [{ id: 1, currency: 'USD' }]
      return { status: 200, body: json(page(rows.map((row) => account(row)), { size: 100 })) }
    }
    if (config.url === '/cashflow/refresh') {
      return options.refresh?.() ?? { status: 200, body: json([fullForecast(30), fullForecast(60), fullForecast(90)]) }
    }
    const { horizon } = config.params as { horizon: ForecastDto['horizonDays'] }
    return options.forecast?.(horizon) ?? { status: 200, body: json(fullForecast(horizon, { projectedEndBalance: '900.00' })) }
  })
  const forecastCalls = () => calls.filter((c) => c.url === '/cashflow/forecast').map((c) => (c.params as { horizon: number }).horizon)
  return { calls, forecastCalls }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const render = (route = '/cashflow') => renderWithProviders(<CashflowPage />, { route })

describe('CashflowPage — forecast', () => {
  it('shows the 30-day forecast with exact balances and change', async () => {
    const s = server()
    render()
    expect(await screen.findByText('Change over 30 days')).toBeInTheDocument()
    const summary = screen.getByText('Balance on 15 Mar 2026').closest('dl') as HTMLElement
    expect(within(summary).getByText('1,000.00 USD')).toBeInTheDocument()
    expect(within(summary).getByText('900.00 USD')).toBeInTheDocument()
    expect(within(summary).getByText('\u2212100.00 USD')).toBeInTheDocument()
    expect(within(summary).getByText('Projected on 14 Apr 2026')).toBeInTheDocument()
    expect(s.forecastCalls()).toEqual([30])
  })

  it('draws the line with one table row per day and axis labels from the data', async () => {
    server({ forecast: (h) => ({ status: 200, body: json(fullForecast(h, { days: dailyPoints(h, (i) => (i === 3 ? '-12.30' : '90071992547409.93')) })) }) })
    const { container } = render()
    const table = await screen.findByRole('table', { name: 'Projected balance, next 30 days' })
    expect(within(table).getAllByRole('row')).toHaveLength(31)
    expect(within(table).getAllByRole('cell')[3]).toHaveTextContent('\u221212.30 USD')
    expect(container.querySelector('[data-axis="max"]')).toHaveTextContent('90,071,992,547,409.93 USD')
    expect(container.querySelector('[data-axis="min"]')).toHaveTextContent('\u221212.30 USD')
  })

  it('switches horizon and keeps it in the URL', async () => {
    const s = server()
    render()
    await screen.findByText('Change over 30 days')
    fireEvent.click(screen.getByRole('radio', { name: '90 days' }))
    expect(await screen.findByText('Change over 90 days')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '90 days' })).toBeChecked()
    expect(s.forecastCalls()).toEqual([30, 90])
  })

  it('reads the horizon from the URL and ignores bad values', async () => {
    const s = server()
    render('/cashflow?horizon=60')
    expect(await screen.findByText('Change over 60 days')).toBeInTheDocument()
    expect(s.forecastCalls()).toEqual([60])
  })

  it('falls back to 30 days for an unknown horizon', async () => {
    const s = server()
    render('/cashflow?horizon=45')
    await screen.findByText('Change over 30 days')
    expect(s.forecastCalls()).toEqual([30])
  })

  it('warns about low confidence', async () => {
    server({ forecast: (h) => ({ status: 200, body: json(fullForecast(h, { lowConfidence: true })) }) })
    render()
    expect(await screen.findByText('There is less than 30 days of history, so this forecast may be far off.')).toBeInTheDocument()
  })

  it('explains a flat forecast without history, and not as low confidence', async () => {
    server({ forecast: (h) => ({ status: 200, body: json(fullForecast(h, { lowConfidence: true, reason: 'NO_HISTORY', projectedEndBalance: '1000.00' })) }) })
    render()
    expect(await screen.findByText(/There are no transactions yet, so the forecast is flat/)).toBeInTheDocument()
    expect(screen.queryByText(/less than 30 days of history/)).not.toBeInTheDocument()
    expect(screen.getByText('+0.00 USD')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    let fail = true
    server({ forecast: () => (fail ? { status: 500, body: '{"status":500}' } : null) })
    render()
    const alert = (await screen.findByText('Could not load the forecast')).closest('[role="alert"]') as HTMLElement
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Change over 30 days')).toBeInTheDocument()
  })
})

describe('CashflowPage — currencies', () => {
  it('hides the forecast when active accounts use several currencies', async () => {
    const s = server({ accounts: [{ id: 1, currency: 'USD' }, { id: 2, currency: 'EUR' }] })
    render()
    expect(await screen.findByRole('note')).toHaveTextContent('Your accounts use EUR and USD.')
    expect(screen.getByText(/The forecast is hidden/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Recalculate' })).not.toBeInTheDocument()
    expect(s.forecastCalls()).toEqual([])
  })

  it('counts only active accounts, as the forecast does', async () => {
    server({ accounts: [{ id: 1, currency: 'USD' }, { id: 2, currency: 'EUR', active: false }] })
    render()
    expect(await screen.findByText('900.00 USD')).toBeInTheDocument()
  })

  it('shows amounts without a code when there are no active accounts', async () => {
    server({ accounts: [] })
    render()
    expect(await screen.findByText('900.00')).toBeInTheDocument()
  })

  it('fetches nothing until currencies load, and nothing if they fail', async () => {
    const gate = deferred()
    const s = server({ accountsGate: gate.promise })
    render()
    expect(await screen.findByRole('status')).toHaveTextContent('Checking account currencies')
    expect(s.forecastCalls()).toEqual([])
    gate.resolve()
    await screen.findByText('Change over 30 days')
  })

  it('shows an error and no forecast when currencies fail', async () => {
    const s = server({ accounts: 'error' })
    render()
    expect(await screen.findByText('Could not check account currencies')).toBeInTheDocument()
    expect(s.forecastCalls()).toEqual([])
  })
})

describe('CashflowPage — recalculate', () => {
  it('recalculates all three horizons and fills them from the response', async () => {
    const s = server()
    // Keep unobserved cache entries (the app keeps them for 5 minutes; the default test client drops them).
    renderWithProviders(<CashflowPage />, { route: '/cashflow', queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) })
    await screen.findByText('Change over 30 days')
    fireEvent.click(screen.getByRole('button', { name: 'Recalculate' }))
    expect(await screen.findByText('Forecast recalculated.')).toBeInTheDocument()
    expect(s.calls.filter((c) => c.url === '/cashflow/refresh' && c.method === 'post')).toHaveLength(1)
    fireEvent.click(screen.getByRole('radio', { name: '60 days' }))
    // Shown at once from the refresh response: no loading state, no wait for a request.
    expect(screen.getByText('Change over 60 days')).toBeInTheDocument()
    expect(screen.queryByText('Loading forecast…')).not.toBeInTheDocument()
  })

  it('explains the once-per-hour limit on 429', async () => {
    server({ refresh: () => ({ status: 429, body: '{"status":429}', headers: { 'Retry-After': '840' } }) })
    render()
    await screen.findByText('Change over 30 days')
    fireEvent.click(screen.getByRole('button', { name: 'Recalculate' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You can recalculate once per hour. Try again in 14 minutes.')
  })
})
