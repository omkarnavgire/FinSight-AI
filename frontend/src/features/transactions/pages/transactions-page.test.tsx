import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { TransactionsPage } from './transactions-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, json, page, transaction } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type AccountRow = NonNullable<Parameters<typeof account>[0]>
type TxRow = NonNullable<Parameters<typeof transaction>[0]>

interface ServerOptions {
  accounts?: AccountRow[]
  transactions?: TxRow[]
  list?: () => MockReply | null
  write?: (config: InternalAxiosRequestConfig) => MockReply | null
}

const DEFAULT_ACCOUNTS: AccountRow[] = [
  { id: 1, name: 'Main', currency: 'USD' },
  { id: 2, name: 'Travel', currency: 'EUR' },
]

function server(options: ServerOptions = {}) {
  const calls: InternalAxiosRequestConfig[] = []
  const rows = options.transactions ?? []
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    if (config.method === 'get' && config.url === '/accounts') {
      return { status: 200, body: json(page((options.accounts ?? DEFAULT_ACCOUNTS).map((row) => account(row)), { size: 100 })) }
    }
    if (config.method === 'get') {
      const forced = options.list?.() ?? null
      if (forced !== null) return forced
      const { page: current, size } = config.params as { page: number; size: number }
      const slice = rows.slice(current * size, current * size + size).map((row) => transaction(row))
      return { status: 200, body: json(page(slice, { page: current, size, totalElements: rows.length })) }
    }
    const forced = options.write?.(config) ?? null
    if (forced !== null) return forced
    return config.method === 'delete' ? { status: 204 } : { status: config.method === 'post' ? 201 : 200, body: json(transaction()) }
  })
  const listParams = () => calls.filter((c) => c.method === 'get' && c.url === '/transactions').map((c) => c.params as Record<string, unknown>)
  const writes = (method: string) => calls.filter((c) => c.method === method)
  return { listParams, writes }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const table = () => screen.findByRole('table', { name: 'Transactions' })
const render = (route = '/transactions') => renderWithProviders(<TransactionsPage />, { route })

describe('TransactionsPage — list', () => {
  it('shows signed amounts in each account’s own currency', async () => {
    server({ transactions: [{ id: 1, accountId: 1, amount: '-42.50' }, { id: 2, accountId: 2, amount: '1250.40', category: 'INCOME', manual: true, description: 'Pay' }] })
    render()
    const t = await table()
    await within(t).findByText('Main')
    expect(within(t).getByText('\u221242.50 USD')).toBeInTheDocument()
    expect(within(t).getByText('1,250.40 EUR')).toBeInTheDocument()
    expect(within(t).getByText('(set by you)')).toBeInTheDocument()
    expect(within(t).getByText('Expense')).toHaveClass('sr-only')
    expect(within(t).getAllByText('Income').some((el) => el.classList.contains('sr-only'))).toBe(true)
  })

  it('reads filters from the URL and sends them exactly', async () => {
    const s = server()
    render('/transactions?category=DINING&minAmount=-100&page=0&accountId=zzz')
    await screen.findByText('No matching transactions')
    expect(s.listParams()[0]).toEqual({ page: 0, size: 20, category: 'DINING', minAmount: '-100.00' })
  })

  it('applies filters, refuses a reversed range, and clears', async () => {
    const s = server({ transactions: [{ id: 1 }] })
    render()
    await table()
    const form = screen.getByRole('form', { name: 'Filter transactions' })
    fireEvent.change(within(form).getByLabelText('From'), { target: { value: '2026-03-10' } })
    fireEvent.change(within(form).getByLabelText('To'), { target: { value: '2026-03-01' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Apply filters' }))
    expect(within(form).getByLabelText('To')).toHaveAccessibleDescription('The end date must not be before the start date.')
    expect(s.listParams()).toHaveLength(1)

    fireEvent.change(within(form).getByLabelText('To'), { target: { value: '2026-03-31' } })
    fireEvent.change(within(form).getByLabelText('Account'), { target: { value: '2' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Apply filters' }))
    await waitFor(() => expect(s.listParams().at(-1)).toEqual({ page: 0, size: 20, accountId: 2, from: '2026-03-10', to: '2026-03-31' }))

    fireEvent.click(within(screen.getByRole('form', { name: 'Filter transactions' })).getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(s.listParams().at(-1)).toEqual({ page: 0, size: 20 }))
  })

  it('pages with the backend page number', async () => {
    const s = server({ transactions: Array.from({ length: 25 }, (_, i) => ({ id: i + 1 })) })
    render()
    await table()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(s.listParams().at(-1)).toEqual({ page: 1, size: 20 })
  })

  it('tells "none yet" from "none match"', async () => {
    server()
    render()
    expect(await screen.findByText('No transactions yet')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    let fail = true
    server({ transactions: [{ id: 1 }], list: () => (fail ? { status: 500, body: '{"status":500}' } : null) })
    render()
    const alert = (await screen.findByText('Could not load transactions')).closest('[role="alert"]') as HTMLElement
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await table()).toBeInTheDocument()
  })

  it('blocks adding without an active account', async () => {
    server({ accounts: [{ id: 1, active: false }] })
    render()
    expect(await screen.findByText('Add an active account before adding transactions.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Add transaction' })[0]).toBeDisabled()
  })
})

async function openAddDialog() {
  const button = (await screen.findAllByRole('button', { name: 'Add transaction' }))[0] as HTMLElement
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  return screen.getByRole('dialog', { name: 'Add transaction' })
}

function fillAdd(dialog: HTMLElement, amount: string, country: string) {
  fireEvent.change(within(dialog).getByLabelText(/^Account/), { target: { value: '1' } })
  fireEvent.change(within(dialog).getByLabelText(/^Amount/), { target: { value: amount } })
  fireEvent.change(within(dialog).getByLabelText(/^Country/), { target: { value: country } })
  fireEvent.click(within(dialog).getByRole('button', { name: 'Add transaction' }))
}

describe('TransactionsPage — add', () => {
  it('sends an expense as an exact negative amount and refreshes', async () => {
    const s = server()
    render()
    const dialog = await openAddDialog()
    fillAdd(dialog, '0.29', 'gb')
    expect(await screen.findByText('Transaction added.')).toBeInTheDocument()
    const body = s.writes('post')[0]?.data as string
    expect(body).toMatch(/^\{"accountId":1,"amount":-0\.29,"transactionDate":"\d{4}-\d{2}-\d{2}","countryCode":"GB"\}$/)
    await waitFor(() => expect(s.listParams().length).toBeGreaterThan(1))
  })

  it('sends income as a positive amount', async () => {
    const s = server()
    render()
    const dialog = await openAddDialog()
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Income' }))
    fillAdd(dialog, '1250.4', 'US')
    await screen.findByText('Transaction added.')
    expect(s.writes('post')[0]?.data).toContain('"amount":1250.40')
  })

  it('shows the 409 inactive-account message and the 400 country error', async () => {
    let reply: MockReply = { status: 409, body: '{"status":409,"detail":"Account is deactivated"}' }
    server({ write: () => reply })
    render()
    const dialog = await openAddDialog()
    fillAdd(dialog, '5', 'US')
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Account is deactivated')

    reply = { status: 400, body: JSON.stringify({ status: 400, detail: 'One or more fields are invalid', errors: ['countryCode: Unknown country code'] }) }
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add transaction' }))
    expect(await within(dialog).findByText('Unknown country code')).toBeInTheDocument()
  })
})

describe('TransactionsPage — edit, category, delete', () => {
  it('edits with the sign kept and PUT', async () => {
    const s = server({ transactions: [{ id: 7, amount: '-42.50', description: 'Market' }] })
    render()
    fireEvent.click(within(await table()).getByRole('button', { name: 'Edit Market' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit transaction' })
    expect(within(dialog).getByLabelText(/^Amount/)).toHaveValue('42.50')
    expect(within(dialog).getByRole('radio', { name: 'Expense' })).toBeChecked()
    fireEvent.change(within(dialog).getByLabelText(/^Amount/), { target: { value: '50' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Transaction updated.')).toBeInTheDocument()
    expect(s.writes('put')[0]?.url).toBe('/transactions/7')
    expect(s.writes('put')[0]?.data).toBe('{"amount":-50.00,"transactionDate":"2026-03-15","category":"GROCERIES","description":"Market"}')
  })

  it('changes only the category with PATCH', async () => {
    const s = server({ transactions: [{ id: 7, description: 'Market' }] })
    render()
    fireEvent.click(within(await table()).getByRole('button', { name: 'Change category of Market' }))
    const dialog = screen.getByRole('dialog', { name: 'Change category' })
    fireEvent.change(within(dialog).getByLabelText(/^Category/), { target: { value: 'DINING' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save category' }))
    expect(await screen.findByText('Category changed.')).toBeInTheDocument()
    expect(s.writes('patch')[0]?.data).toBe('{"category":"DINING"}')
  })

  it('asks before deleting, then deletes', async () => {
    const s = server({ transactions: [{ id: 7, description: 'Market' }] })
    render()
    fireEvent.click(within(await table()).getByRole('button', { name: 'Delete Market' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete transaction?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Transaction deleted.')).toBeInTheDocument()
    expect(s.writes('delete')[0]?.url).toBe('/transactions/7')
  })

  it('keeps the delete dialog open with the error', async () => {
    server({ transactions: [{ id: 7, description: 'Market' }], write: () => ({ status: 404, body: '{"status":404,"detail":"Transaction not found"}' }) })
    render()
    fireEvent.click(within(await table()).getByRole('button', { name: 'Delete Market' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete transaction?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Transaction not found')
  })
})
