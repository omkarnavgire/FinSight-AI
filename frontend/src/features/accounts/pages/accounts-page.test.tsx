import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { AccountsPage } from './accounts-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import type { AccountDto } from '@/shared/types/api'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { account, json, page } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type Row = NonNullable<Parameters<typeof account>[0]>

/** A tiny stand-in for the accounts endpoints: paging, create and deactivate on an in-memory list. */
function accountsBackend(rows: Row[], overrides: { list?: () => MockReply | null; create?: () => MockReply; remove?: () => MockReply } = {}) {
  const store = rows.map((row, index) => ({ id: index + 1, ...row }))
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    if (config.method === 'get') {
      const forced = overrides.list?.() ?? null
      if (forced !== null) return forced
      const { page: current = 0, size = 20 } = (config.params ?? {}) as { page?: number; size?: number }
      const slice = store.slice(current * size, current * size + size).map((row) => account(row))
      return { status: 200, body: json(page(slice, { page: current, size, totalElements: store.length })) }
    }
    if (config.method === 'post') {
      if (overrides.create) return overrides.create()
      const created = { id: store.length + 1, name: 'Card', type: 'CREDIT' as AccountDto['type'], balance: '-250.00', currency: 'EUR' }
      store.push(created)
      return { status: 201, body: json(account(created)) }
    }
    if (overrides.remove) return overrides.remove()
    const id = Number(config.url?.split('/').pop())
    const target = store.find((row) => row.id === id)
    if (target !== undefined) target.active = false
    return { status: 204 }
  })
  return { calls, store }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const desktopTable = () => screen.findByRole('table', { name: 'Your accounts' })

describe('AccountsPage — list and totals', () => {
  it('lists accounts with exact balances in their own currency', async () => {
    accountsBackend([
      { name: 'Main', balance: '1250.40', currency: 'USD' },
      { name: 'Card', type: 'CREDIT', balance: '-250.00', currency: 'EUR' },
    ])
    renderWithProviders(<AccountsPage />)
    const table = await desktopTable()
    expect(within(table).getByText('1,250.40 USD')).toBeInTheDocument()
    expect(within(table).getByText('\u2212250.00 EUR')).toBeInTheDocument()
  })

  it('keeps totals per currency and warns instead of combining them', async () => {
    accountsBackend([
      { balance: '1000.10', currency: 'USD' },
      { balance: '0.20', currency: 'USD' },
      { balance: '500.00', currency: 'EUR' },
    ])
    renderWithProviders(<AccountsPage />)
    const totals = await screen.findByRole('region', { name: 'Total balance' })
    expect(within(totals).getByText('1,000.30 USD')).toBeInTheDocument()
    expect(within(totals).getByText('500.00 EUR')).toBeInTheDocument()
    expect(within(totals).getByRole('note')).toHaveTextContent('Your accounts use EUR and USD.')
    expect(within(totals).queryByText(/1,500\.30/)).not.toBeInTheDocument()
  })

  it('shows no currency warning for a single currency', async () => {
    accountsBackend([{ balance: '1.00' }, { balance: '2.00' }])
    renderWithProviders(<AccountsPage />)
    const totals = await screen.findByRole('region', { name: 'Total balance' })
    expect(within(totals).getByText('3.00 USD')).toBeInTheDocument()
    expect(within(totals).queryByRole('note')).not.toBeInTheDocument()
  })

  it('leaves inactive accounts out of totals and offers no deactivate for them', async () => {
    accountsBackend([{ name: 'Old', balance: '99.00', active: false }, { name: 'Main', balance: '1.00' }])
    renderWithProviders(<AccountsPage />)
    const totals = await screen.findByRole('region', { name: 'Total balance' })
    expect(within(totals).getByText('1.00 USD')).toBeInTheDocument()
    const table = await desktopTable()
    expect(within(table).getByText('Inactive')).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: 'Deactivate Old' })).not.toBeInTheDocument()
    expect(within(table).getByRole('button', { name: 'Deactivate Main' })).toBeInTheDocument()
  })

  it('shows an empty state with a call to action', async () => {
    accountsBackend([])
    renderWithProviders(<AccountsPage />)
    expect(await screen.findByText('No accounts yet')).toBeInTheDocument()
    expect(screen.getByText('No active accounts, so there is no total yet.')).toBeInTheDocument()
  })

  it('shows an error with retry, then the data', async () => {
    let fail = true
    accountsBackend([{ name: 'Main' }], { list: () => (fail ? { status: 500, body: '{"status":500}' } : null) })
    renderWithProviders(<AccountsPage />)
    const alert = (await screen.findByText('Could not load accounts')).closest('[role="alert"]') as HTMLElement
    expect(alert).toHaveTextContent('Something went wrong on our side.')
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await desktopTable()).toHaveTextContent('Main')
  })

  it('pages with the backend page number', async () => {
    const backend = accountsBackend(Array.from({ length: 25 }, (_, i) => ({ name: `Acc ${i + 1}` })))
    renderWithProviders(<AccountsPage />)
    await desktopTable()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(backend.calls.some((c) => (c.params as { page: number; size: number }).page === 1 && (c.params as { size: number }).size === 20)).toBe(true)
  })
})

describe('AccountsPage — create', () => {
  it('checks the form before sending', async () => {
    const backend = accountsBackend([])
    renderWithProviders(<AccountsPage />)
    fireEvent.click((await screen.findAllByRole('button', { name: 'New account' }))[0] as HTMLElement)
    const dialog = screen.getByRole('dialog', { name: 'New account' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create account' }))
    expect(within(dialog).getByLabelText(/^Name/)).toHaveAccessibleDescription('Enter a name.')
    expect(within(dialog).getByLabelText(/^Opening balance/)).toHaveAccessibleDescription(/Enter an amount\./)
    expect(backend.calls.filter((c) => c.method === 'post')).toHaveLength(0)
  })

  it('creates an account with exact money and refreshes the list', async () => {
    const backend = accountsBackend([])
    renderWithProviders(<AccountsPage />)
    fireEvent.click((await screen.findAllByRole('button', { name: 'New account' }))[0] as HTMLElement)
    const dialog = screen.getByRole('dialog', { name: 'New account' })
    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'Card' } })
    fireEvent.change(within(dialog).getByLabelText(/^Type/), { target: { value: 'CREDIT' } })
    fireEvent.change(within(dialog).getByLabelText(/^Opening balance/), { target: { value: '-250' } })
    fireEvent.change(within(dialog).getByLabelText(/^Currency/), { target: { value: 'eur' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('Card was created.')).toBeInTheDocument()
    const post = backend.calls.find((c) => c.method === 'post')
    expect(post?.data).toBe('{"name":"Card","type":"CREDIT","openingBalance":-250.00,"currency":"EUR"}')
    expect(await desktopTable()).toHaveTextContent('\u2212250.00 EUR')
  })

  it('shows backend field errors on their fields', async () => {
    const body = JSON.stringify({ status: 400, detail: 'One or more fields are invalid', errors: ['currency: Currency must be three uppercase letters'] })
    accountsBackend([], { create: () => ({ status: 400, body }) })
    renderWithProviders(<AccountsPage />)
    fireEvent.click((await screen.findAllByRole('button', { name: 'New account' }))[0] as HTMLElement)
    const dialog = screen.getByRole('dialog', { name: 'New account' })
    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'X' } })
    fireEvent.change(within(dialog).getByLabelText(/^Type/), { target: { value: 'SAVINGS' } })
    fireEvent.change(within(dialog).getByLabelText(/^Opening balance/), { target: { value: '1' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create account' }))
    expect(await within(dialog).findByText('Currency must be three uppercase letters')).toBeInTheDocument()
  })
})

describe('AccountsPage — deactivate', () => {
  it('asks first, deactivates, confirms and refreshes', async () => {
    const backend = accountsBackend([{ name: 'Main' }])
    renderWithProviders(<AccountsPage />)
    fireEvent.click(within(await desktopTable()).getByRole('button', { name: 'Deactivate Main' }))
    const dialog = screen.getByRole('dialog', { name: 'Deactivate account?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }))

    expect(await screen.findByText('Main is now inactive.')).toBeInTheDocument()
    expect(backend.calls.find((c) => c.method === 'delete')?.url).toBe('/accounts/1')
    await waitFor(async () => expect(within(await desktopTable()).getByText('Inactive')).toBeInTheDocument())
  })

  it('keeps the dialog open with the error when deactivation fails', async () => {
    accountsBackend([{ name: 'Main' }], { remove: () => ({ status: 404, body: '{"status":404,"detail":"Account not found"}' }) })
    renderWithProviders(<AccountsPage />)
    fireEvent.click(within(await desktopTable()).getByRole('button', { name: 'Deactivate Main' }))
    const dialog = screen.getByRole('dialog', { name: 'Deactivate account?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Account not found')
  })
})
