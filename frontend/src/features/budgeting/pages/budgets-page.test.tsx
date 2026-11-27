import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { BudgetsPage } from './budgets-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, budget, budgetProgress, json, page } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type BudgetRow = NonNullable<Parameters<typeof budget>[0]>
type ProgressRow = NonNullable<Parameters<typeof budgetProgress>[0]>

interface Options {
  currencies?: string[] | 'error'
  budgets?: BudgetRow[]
  progress?: (id: number) => ProgressRow | MockReply
  list?: () => MockReply | null
  write?: () => MockReply | null
}

function server(options: Options = {}) {
  const calls: InternalAxiosRequestConfig[] = []
  const rows = options.budgets ?? [{ id: 1, category: 'DINING' }]
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    const url = config.url ?? ''
    if (config.method === 'get' && url === '/accounts') {
      if (options.currencies === 'error') return { status: 500, body: '{"status":500}' }
      const codes = options.currencies ?? ['USD']
      return { status: 200, body: json(page(codes.map((currency, i) => account({ id: i + 1, currency })), { size: 100 })) }
    }
    if (config.method === 'get' && url.endsWith('/progress')) {
      const id = Number(url.split('/')[2])
      const row = options.progress?.(id) ?? { budgetId: id }
      return 'status' in row ? (row as MockReply) : { status: 200, body: json(budgetProgress(row as ProgressRow)) }
    }
    if (config.method === 'get') {
      const forced = options.list?.() ?? null
      if (forced !== null) return forced
      return { status: 200, body: json(page(rows.map((row) => budget(row)))) }
    }
    const forced = options.write?.() ?? null
    if (forced !== null) return forced
    if (config.method === 'delete') return { status: 204 }
    return { status: config.method === 'post' ? 201 : 200, body: json(budget({ id: 9, category: 'DINING' })) }
  })
  return { calls, writes: (method: string) => calls.filter((c) => c.method === method) }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const card = async (name: string) => (await screen.findByRole('heading', { name })).closest('li') as HTMLElement

describe('BudgetsPage — progress', () => {
  it('shows this month, the limit and exact progress in the single currency', async () => {
    const s = server()
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByText('March 2026')).toBeInTheDocument()
    const dining = await card('Dining')
    expect(within(dining).getByText('300.00 USD')).toBeInTheDocument()
    expect(await within(dining).findByText('120.00 USD')).toBeInTheDocument()
    expect(within(dining).getByText('180.00 USD')).toBeInTheDocument()
    expect(within(dining).getByRole('progressbar', { name: 'Dining budget' })).toHaveAttribute('aria-valuetext', '40.00% used, on track')
    const progressCall = s.calls.find((c) => c.url === '/budgets/1/progress')
    expect(progressCall?.params).toEqual({ year: 2026, month: 3 })
  })

  it('shows overspending and the alerts that fired', async () => {
    server({ progress: () => ({ spent: '320.00', remaining: '-20.00', percentUsed: '106.67', alertsFired: [80, 100] }) })
    renderWithProviders(<BudgetsPage />)
    const dining = await card('Dining')
    expect(await within(dining).findByText('Over by')).toBeInTheDocument()
    expect(within(dining).getByText('20.00 USD')).toBeInTheDocument()
    expect(within(dining).getByText('80% alert sent')).toBeInTheDocument()
    expect(within(dining).getByText('100% alert sent')).toBeInTheDocument()
    expect(within(dining).getByText('Over the limit')).toBeInTheDocument()
  })

  it('holds back spending when accounts use several currencies', async () => {
    server({ currencies: ['USD', 'EUR'] })
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByRole('note')).toHaveTextContent('Your accounts use EUR and USD.')
    const dining = await card('Dining')
    expect(await within(dining).findByText('Progress is not shown because your accounts use several currencies.')).toBeInTheDocument()
    expect(within(dining).queryByText('Spent')).not.toBeInTheDocument()
    expect(within(dining).queryByRole('progressbar')).not.toBeInTheDocument()
    expect(within(dining).getByText('300.00')).toBeInTheDocument()
  })

  it('hides progress when account currencies cannot load', async () => {
    server({ currencies: 'error' })
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByText('Could not check account currencies')).toBeInTheDocument()
    const dining = await card('Dining')
    expect(await within(dining).findByText('Progress is hidden until your account currencies load.')).toBeInTheDocument()
  })

  it('lets one card fail without hiding the others', async () => {
    server({
      budgets: [{ id: 1, category: 'DINING' }, { id: 2, category: 'TRANSPORT' }],
      progress: (id) => (id === 1 ? { status: 500, body: '{"status":500}' } : { budgetId: 2, category: 'TRANSPORT', spent: '5.00' }),
    })
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByText('Could not load Dining progress')).toBeInTheDocument()
    expect(await within(await card('Transport')).findByText('5.00 USD')).toBeInTheDocument()
  })
})

describe('BudgetsPage — list states', () => {
  it('shows an empty state', async () => {
    server({ budgets: [] })
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByText('No budgets this month')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    let fail = true
    server({ list: () => (fail ? { status: 500, body: '{"status":500}' } : null) })
    renderWithProviders(<BudgetsPage />)
    const alert = (await screen.findByText('Could not load budgets')).closest('[role="alert"]') as HTMLElement
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await card('Dining')).toBeInTheDocument()
  })
})

async function openCreate() {
  fireEvent.click((await screen.findAllByRole('button', { name: 'New budget' }))[0] as HTMLElement)
  return screen.getByRole('dialog', { name: 'New budget' })
}

describe('BudgetsPage — create, edit, delete', () => {
  it('offers no INCOME category and checks the form first', async () => {
    const s = server({ budgets: [] })
    renderWithProviders(<BudgetsPage />)
    const dialog = await openCreate()
    const options = Array.from((within(dialog).getByLabelText(/^Category/) as HTMLSelectElement).options, (o) => o.value)
    expect(options).not.toContain('INCOME')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create budget' }))
    expect(within(dialog).getByLabelText(/^Category/)).toHaveAccessibleDescription(/Choose a spending category\./)
    expect(s.writes('post')).toHaveLength(0)
  })

  it('creates with an exact limit and refreshes', async () => {
    const s = server({ budgets: [] })
    renderWithProviders(<BudgetsPage />)
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText(/^Category/), { target: { value: 'DINING' } })
    fireEvent.change(within(dialog).getByLabelText(/^Monthly limit/), { target: { value: '250.5' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create budget' }))
    expect(await screen.findByText('Dining budget created.')).toBeInTheDocument()
    expect(s.writes('post')[0]?.data).toBe('{"category":"DINING","monthlyLimit":250.50}')
    await waitFor(() => expect(s.calls.filter((c) => c.url === '/budgets' && c.method === 'get').length).toBeGreaterThan(1))
  })

  it('shows the 409 duplicate message', async () => {
    server({ budgets: [], write: () => ({ status: 409, body: '{"status":409,"detail":"A budget for this category and period already exists"}' }) })
    renderWithProviders(<BudgetsPage />)
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText(/^Category/), { target: { value: 'DINING' } })
    fireEvent.change(within(dialog).getByLabelText(/^Monthly limit/), { target: { value: '10' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create budget' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A budget for this category and period already exists')
  })

  it('edits only the limit with PUT', async () => {
    const s = server()
    renderWithProviders(<BudgetsPage />)
    fireEvent.click(within(await card('Dining')).getByRole('button', { name: 'Edit Dining budget' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit budget' })
    expect(within(dialog).getByLabelText(/^Monthly limit/)).toHaveValue('300.00')
    fireEvent.change(within(dialog).getByLabelText(/^Monthly limit/), { target: { value: '0' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save limit' }))
    expect(within(dialog).getByLabelText(/^Monthly limit/)).toHaveAccessibleDescription(/Enter a limit above zero\./)
    fireEvent.change(within(dialog).getByLabelText(/^Monthly limit/), { target: { value: '9999999999999.99' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save limit' }))
    expect(await screen.findByText('Dining budget updated.')).toBeInTheDocument()
    expect(s.writes('put')[0]?.url).toBe('/budgets/1')
    expect(s.writes('put')[0]?.data).toBe('{"monthlyLimit":9999999999999.99}')
  })

  it('asks before deleting, then deletes', async () => {
    const s = server()
    renderWithProviders(<BudgetsPage />)
    fireEvent.click(within(await card('Dining')).getByRole('button', { name: 'Delete Dining budget' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete budget?' })).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Dining budget deleted.')).toBeInTheDocument()
    expect(s.writes('delete')[0]?.url).toBe('/budgets/1')
  })

  it('keeps the delete dialog open with the error', async () => {
    server({ write: () => ({ status: 404, body: '{"status":404,"detail":"Budget not found"}' }) })
    renderWithProviders(<BudgetsPage />)
    fireEvent.click(within(await card('Dining')).getByRole('button', { name: 'Delete Dining budget' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete budget?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Budget not found')
  })
})
