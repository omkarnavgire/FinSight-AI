import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { DashboardPage } from './dashboard-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { deferred, type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, budget, budgetProgress, fraudAlert, json, page, transaction, trend } from '@/test/fixtures'
import { fullForecast } from '@/test/forecast-fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type Area = 'accounts' | 'trend' | 'budgets' | 'progress' | 'forecast' | 'fraud' | 'transactions'
type Override = Partial<Record<Area, () => MockReply | Promise<MockReply>>>

const ERROR: MockReply = { status: 500, body: '{"status":500}' }
const PERCENTS: Record<number, string> = { 1: '40.00', 2: '85.00', 3: '120.00' }

function areaOf(url: string): Area {
  if (url === '/accounts') return 'accounts'
  if (url.startsWith('/analytics')) return 'trend'
  if (url.endsWith('/progress')) return 'progress'
  if (url.startsWith('/budgets')) return 'budgets'
  if (url.startsWith('/cashflow')) return 'forecast'
  if (url.startsWith('/fraud')) return 'fraud'
  return 'transactions'
}

const DEFAULTS: Record<Area, (config: InternalAxiosRequestConfig) => MockReply> = {
  accounts: () => ({ status: 200, body: json(page([account({ id: 1, name: 'Main', balance: '1000.10' }), account({ id: 2, name: 'Savings', balance: '250.30' })], { size: 100 })) }),
  trend: () => ({ status: 200, body: json(trend([{ year: 2026, month: 2, spent: '75.00' }, { year: 2026, month: 3, spent: '120.40' }])) }),
  budgets: () => ({ status: 200, body: json(page([budget({ id: 1, category: 'DINING' }), budget({ id: 2, category: 'TRANSPORT' }), budget({ id: 3, category: 'OTHER' })])) }),
  progress: (config) => {
    const id = Number(config.url?.split('/')[2])
    return { status: 200, body: json(budgetProgress({ budgetId: id, percentUsed: PERCENTS[id] ?? '0.00' })) }
  },
  forecast: () => ({ status: 200, body: json(fullForecast(30, { projectedEndBalance: '900.00' })) }),
  fraud: () => ({ status: 200, body: json(page([fraudAlert()], { size: 1, totalElements: 3 })) }),
  transactions: () => ({
    status: 200,
    body: json(page([transaction({ id: 1, accountId: 1, amount: '-42.50', description: 'Market' }), transaction({ id: 2, accountId: 2, amount: '1250.40', category: 'INCOME', description: 'Salary' })])),
  }),
}

function server(override: Override = {}) {
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter(async (config) => {
    calls.push(config)
    const area = areaOf(config.url ?? '')
    return (await override[area]?.()) ?? DEFAULTS[area](config)
  })
  const count = (area: Area) => calls.filter((c) => areaOf(c.url ?? '') === area).length
  return { calls, count }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const card = (name: string) => screen.findByRole('region', { name })

describe('Dashboard — all six cards', () => {
  it('shows every card with exact amounts in one currency', async () => {
    server()
    renderWithProviders(<DashboardPage />)
    expect(await within(await card('Total balance')).findByText('1,250.40 USD')).toBeInTheDocument()
    expect(await within(await card("This month's spending")).findByText('120.40 USD')).toBeInTheDocument()
    expect(await within(await card('Budgets')).findByText('3 budgets this month: 1 near the limit, 1 over the limit')).toBeInTheDocument()
    expect(within(await card('Budgets')).getAllByRole('progressbar')).toHaveLength(3)
    expect(await within(await card('30-day forecast')).findByText('900.00 USD')).toBeInTheDocument()
    expect(await within(await card('Fraud alerts')).findByText('3 unacknowledged alerts')).toBeInTheDocument()
    const recent = await card('Recent transactions')
    expect(await within(recent).findByText('\u221242.50 USD')).toBeInTheDocument()
    expect(within(recent).getByText('1,250.40 USD')).toBeInTheDocument()
    expect(within(recent).getByText('Expense')).toHaveClass('sr-only')
  })

  it('links each card to its module', async () => {
    server()
    renderWithProviders(<DashboardPage />)
    const links = [
      ['Total balance', 'Open accounts', '/accounts'],
      ["This month's spending", 'Open analytics', '/analytics'],
      ['Budgets', 'Open budgets', '/budgets'],
      ['30-day forecast', 'Open cash flow', '/cashflow'],
      ['Fraud alerts', 'Review fraud alerts', '/fraud-alerts'],
      ['Recent transactions', 'Open transactions', '/transactions'],
    ]
    for (const [region, name, href] of links) {
      expect(within(await card(region as string)).getByRole('link', { name })).toHaveAttribute('href', href)
    }
  })

  it('loads shared data once and never recalculates the forecast', async () => {
    const s = server()
    renderWithProviders(<DashboardPage />)
    await within(await card('30-day forecast')).findByText('900.00 USD')
    await within(await card('Recent transactions')).findByText('Market')
    expect(s.count('accounts')).toBe(1)
    expect(s.count('fraud')).toBe(1)
    expect(s.calls.filter((c) => c.url === '/cashflow/forecast').map((c) => c.params)).toEqual([{ horizon: 30 }])
    expect(s.calls.some((c) => c.method === 'post')).toBe(false)
  })
})

describe('Dashboard — currencies', () => {
  it('keeps balances per currency and hides every backend sum when currencies are mixed', async () => {
    const s = server({
      accounts: () => ({ status: 200, body: json(page([account({ id: 1, balance: '10.00', currency: 'USD' }), account({ id: 2, balance: '5.00', currency: 'EUR' })], { size: 100 })) }),
    })
    renderWithProviders(<DashboardPage />)
    expect(await screen.findByRole('note')).toHaveTextContent('Your accounts use EUR and USD.')
    const balance = await card('Total balance')
    expect(within(balance).getByText('10.00 USD')).toBeInTheDocument()
    expect(within(balance).getByText('5.00 EUR')).toBeInTheDocument()
    expect(within(balance).queryByText(/15\.00/)).not.toBeInTheDocument()
    for (const name of ["This month's spending", '30-day forecast']) {
      expect(within(await card(name)).getByText(/Hidden: your accounts use EUR and USD/)).toBeInTheDocument()
    }
    const budgets = await card('Budgets')
    expect(await within(budgets).findByText('3 budgets this month')).toBeInTheDocument()
    expect(within(budgets).queryByRole('progressbar')).not.toBeInTheDocument()
    expect([s.count('trend'), s.count('progress'), s.count('forecast')]).toEqual([0, 0, 0])
  })

  it('shows recent amounts in each account’s own currency', async () => {
    server({
      accounts: () => ({ status: 200, body: json(page([account({ id: 1, currency: 'USD' }), account({ id: 2, currency: 'EUR' })], { size: 100 })) }),
    })
    renderWithProviders(<DashboardPage />)
    const recent = await card('Recent transactions')
    expect(await within(recent).findByText('1,250.40 EUR')).toBeInTheDocument()
    expect(within(recent).getByText('\u221242.50 USD')).toBeInTheDocument()
  })
})

describe('Dashboard — independent states', () => {
  it('keeps other cards working while one is still loading', async () => {
    const gate = deferred()
    server({ transactions: async () => { await gate.promise; return DEFAULTS.transactions({} as InternalAxiosRequestConfig) } })
    renderWithProviders(<DashboardPage />)
    expect(await within(await card('Fraud alerts')).findByText('3 unacknowledged alerts')).toBeInTheDocument()
    expect(within(await card('Recent transactions')).getByRole('status')).toHaveTextContent('Loading transactions')
    gate.resolve()
    expect(await within(await card('Recent transactions')).findByText('Market')).toBeInTheDocument()
  })

  it('lets one card fail and retry while the rest render', async () => {
    let fail = true
    server({ fraud: () => (fail ? ERROR : DEFAULTS.fraud({} as InternalAxiosRequestConfig)) })
    renderWithProviders(<DashboardPage />)
    const fraud = await card('Fraud alerts')
    const alert = (await within(fraud).findByText('Could not load fraud alerts')).closest('[role="alert"]') as HTMLElement
    expect(await within(await card('Total balance')).findByText('1,250.40 USD')).toBeInTheDocument()
    expect(await within(await card('Budgets')).findByText(/1 over the limit/)).toBeInTheDocument()
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await within(fraud).findByText('3 unacknowledged alerts')).toBeInTheDocument()
  })

  it('hides currency-dependent sums when currencies fail, but shows the rest', async () => {
    server({ accounts: () => ERROR })
    renderWithProviders(<DashboardPage />)
    expect(await within(await card('Total balance')).findByText('Could not load balances')).toBeInTheDocument()
    expect(await within(await card("This month's spending")).findByText('Could not check account currencies')).toBeInTheDocument()
    expect(await within(await card('Fraud alerts')).findByText('3 unacknowledged alerts')).toBeInTheDocument()
    expect((await within(await card('Recent transactions')).findAllByText(/Account #1/)).length).toBeGreaterThan(0)
  })

  it('shows empty states', async () => {
    server({
      accounts: () => ({ status: 200, body: json(page([], { size: 100 })) }),
      trend: () => ({ status: 200, body: json(trend([{ year: 2026, month: 3, spent: '0.00' }])) }),
      budgets: () => ({ status: 200, body: json(page([])) }),
      fraud: () => ({ status: 200, body: json(page([], { size: 1, totalElements: 0 })) }),
      transactions: () => ({ status: 200, body: json(page([])) }),
    })
    renderWithProviders(<DashboardPage />)
    expect(await screen.findByText('No active accounts yet.')).toBeInTheDocument()
    expect(await screen.findByText('No spending this month yet.')).toBeInTheDocument()
    expect(await screen.findByText('No budgets this month.')).toBeInTheDocument()
    expect(await screen.findByText('No open alerts.')).toBeInTheDocument()
    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument()
  })
})

describe('Dashboard — forecast messages', () => {
  it('explains a flat forecast without history', async () => {
    server({ forecast: () => ({ status: 200, body: json(fullForecast(30, { lowConfidence: true, reason: 'NO_HISTORY' })) }) })
    renderWithProviders(<DashboardPage />)
    const forecast = await card('30-day forecast')
    expect(await within(forecast).findByText('No transactions yet, so the forecast is flat.')).toBeInTheDocument()
    expect(within(forecast).queryByText(/Low confidence/)).not.toBeInTheDocument()
  })

  it('warns about low confidence', async () => {
    server({ forecast: () => ({ status: 200, body: json(fullForecast(30, { lowConfidence: true })) }) })
    renderWithProviders(<DashboardPage />)
    expect(await within(await card('30-day forecast')).findByText('Low confidence: less than 30 days of history.')).toBeInTheDocument()
  })
})
