import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { AnalyticsPage } from './analytics-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { deferred, type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, breakdown, json, page, trend } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

const MONTHS = [
  { year: 2025, month: 10, spent: '10.00' },
  { year: 2025, month: 11, spent: '0.00' },
  { year: 2025, month: 12, spent: '250.50' },
  { year: 2026, month: 1, spent: '0.29' },
  { year: 2026, month: 2, spent: '75.00' },
  { year: 2026, month: 3, spent: '120.00' },
]

interface Options {
  currencies?: string[] | 'error'
  accountsGate?: Promise<void>
  breakdownBody?: string
  trendBody?: string
  topBody?: string
  fail?: { breakdown?: () => boolean }
}

function server(options: Options = {}) {
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter(async (config): Promise<MockReply> => {
    calls.push(config)
    const url = config.url ?? ''
    if (url === '/accounts') {
      if (options.accountsGate) await options.accountsGate
      if (options.currencies === 'error') return { status: 500, body: '{"status":500}' }
      const codes = options.currencies ?? ['USD']
      return { status: 200, body: json(page(codes.map((currency, i) => account({ id: i + 1, currency })), { size: 100 })) }
    }
    if (url.endsWith('category-breakdown')) {
      if (options.fail?.breakdown?.()) return { status: 500, body: '{"status":500}' }
      return {
        status: 200,
        body: options.breakdownBody ?? json(breakdown([{ category: 'DINING', amount: '90.00', percent: '75.00' }, { category: 'TRANSPORT', amount: '30.00', percent: '25.00' }], '120.00')),
      }
    }
    if (url.endsWith('monthly-trend')) return { status: 200, body: options.trendBody ?? json(trend(MONTHS)) }
    return {
      status: 200,
      body: options.topBody ?? json(breakdown([{ category: 'GROCERIES', amount: '90071992547409.93', percent: '100.00' }], '90071992547409.93', '2026-02-14', '2026-03-15')),
    }
  })
  const analyticsCalls = () => calls.filter((c) => (c.url ?? '').startsWith('/analytics'))
  return { calls, analyticsCalls }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const section = async (name: string) => screen.findByRole('region', { name })

describe('AnalyticsPage — single currency', () => {
  it('shows the month breakdown with exact amounts, shares and total', async () => {
    const s = server()
    renderWithProviders(<AnalyticsPage />)
    const block = await section('Spending by category')
    const table = await within(block).findByRole('table', { name: 'Spending by category' })
    expect(within(table).getAllByRole('cell').map((c) => c.textContent)).toEqual(['90.00 USD (75.00%)', '30.00 USD (25.00%)'])
    expect(within(block).getByText('120.00 USD')).toBeInTheDocument()
    expect(within(block).getByText(/1 Mar 2026 to 31 Mar 2026/)).toBeInTheDocument()
    expect(s.analyticsCalls().find((c) => c.url === '/analytics/category-breakdown')?.params).toBeUndefined()
  })

  it('shows six months oldest first with exact bar labels', async () => {
    server()
    const { container } = renderWithProviders(<AnalyticsPage />)
    const block = await section('Monthly trend')
    const table = await within(block).findByRole('table', { name: 'Monthly spending, October 2025 to March 2026' })
    expect(within(table).getAllByRole('rowheader').map((c) => c.textContent)).toEqual(['Oct', 'Nov', 'Dec', 'Jan', 'Feb', 'Mar'])
    const labels = Array.from(container.querySelectorAll('[data-bar-value]'), (el) => el.textContent)
    expect(labels).toEqual(['10.00 USD', '0.00 USD', '250.50 USD', '0.29 USD', '75.00 USD', '120.00 USD'])
  })

  it('shows the top categories for the last 30 days, exactly', async () => {
    server()
    renderWithProviders(<AnalyticsPage />)
    const block = await section('Top categories, last 30 days')
    const table = await within(block).findByRole('table', { name: 'Top five categories' })
    expect(within(table).getByRole('cell')).toHaveTextContent('90,071,992,547,409.93 USD (100.00%)')
    expect(within(block).getByText(/14 Feb 2026 to 15 Mar 2026/)).toBeInTheDocument()
  })

  it('sends year and month together when the month changes', async () => {
    const s = server()
    renderWithProviders(<AnalyticsPage />)
    await section('Spending by category')
    const month = await screen.findByLabelText('Month')
    await waitFor(() => expect(month).toBeEnabled())
    expect(month).toHaveValue('3')
    fireEvent.change(month, { target: { value: '1' } })
    await waitFor(() => expect(s.analyticsCalls().at(-1)?.params).toEqual({ year: 2026, month: 1 }))
    fireEvent.change(screen.getByLabelText('Year'), { target: { value: '2025' } })
    await waitFor(() => expect(s.analyticsCalls().at(-1)?.params).toEqual({ year: 2025, month: 1 }))
  })
})

describe('AnalyticsPage — currency states', () => {
  it('hides all monetary analytics for mixed currencies and fetches none', async () => {
    const s = server({ currencies: ['USD', 'EUR'] })
    renderWithProviders(<AnalyticsPage />)
    expect(await screen.findByRole('note')).toHaveTextContent('Your accounts use EUR and USD.')
    expect(screen.getByText(/Spending charts and totals are hidden/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(s.analyticsCalls()).toHaveLength(0)
  })

  it('shows amounts without a currency code when there are no accounts', async () => {
    server({ currencies: [] })
    renderWithProviders(<AnalyticsPage />)
    const table = await within(await section('Spending by category')).findByRole('table', { name: 'Spending by category' })
    expect(within(table).getAllByRole('cell')[0]).toHaveTextContent('90.00 (75.00%)')
  })

  it('waits for currencies before fetching anything', async () => {
    const gate = deferred()
    const s = server({ accountsGate: gate.promise })
    renderWithProviders(<AnalyticsPage />)
    expect(await screen.findByRole('status')).toHaveTextContent('Checking account currencies')
    expect(s.analyticsCalls()).toHaveLength(0)
    gate.resolve()
    expect(await section('Spending by category')).toBeInTheDocument()
  })

  it('shows an error and no analytics when currencies fail', async () => {
    const s = server({ currencies: 'error' })
    renderWithProviders(<AnalyticsPage />)
    expect(await screen.findByText('Could not check account currencies')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(s.analyticsCalls()).toHaveLength(0)
  })
})

describe('AnalyticsPage — sections', () => {
  it('lets one section fail and retry while the others render', async () => {
    let fail = true
    server({ fail: { breakdown: () => fail } })
    renderWithProviders(<AnalyticsPage />)
    const block = await section('Spending by category')
    const alert = (await within(block).findByText('Could not load spending by category')).closest('[role="alert"]') as HTMLElement
    expect(await within(await section('Monthly trend')).findByRole('table')).toBeInTheDocument()
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await within(block).findByRole('table', { name: 'Spending by category' })).toBeInTheDocument()
  })

  it('shows empty states for empty datasets', async () => {
    server({
      breakdownBody: json(breakdown([], '0.00')),
      trendBody: json(trend(MONTHS.map((m) => ({ ...m, spent: '0.00' })))),
      topBody: json(breakdown([], '0.00')),
    })
    renderWithProviders(<AnalyticsPage />)
    expect(await screen.findByText('No spending in March 2026')).toBeInTheDocument()
    expect(await screen.findByText('No spending in the last six months')).toBeInTheDocument()
    expect(await screen.findByText('No spending in the last 30 days')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
