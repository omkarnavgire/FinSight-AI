import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { InternalAxiosRequestConfig } from 'axios'
import { FraudAlertsPage } from './fraud-alerts-page'
import { resetSessionRestore } from '@/features/auth/lib/restore-session'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { deferred, type MockReply, mockAdapter } from '@/test/mock-adapter'
import { fraudAlert, json, page } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

type AlertRow = NonNullable<Parameters<typeof fraudAlert>[0]>

/** In-memory open alerts: acknowledging removes one, as the backend's list does. */
function server(rows: AlertRow[], options: { list?: () => MockReply | null; ack?: () => MockReply | Promise<MockReply> } = {}) {
  const open = rows.map((row, index) => ({ id: index + 1, ...row }))
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = mockAdapter(async (config) => {
    calls.push(config)
    if (config.method === 'patch') {
      if (options.ack) return options.ack()
      const id = Number(config.url?.split('/')[3])
      const index = open.findIndex((row) => row.id === id)
      const [removed] = open.splice(index, 1)
      return { status: 200, body: json(fraudAlert({ ...removed, acknowledged: true, acknowledgedAt: '2026-03-16T08:00:00' })) }
    }
    const forced = options.list?.() ?? null
    if (forced !== null) return forced
    const { page: current, size } = config.params as { page: number; size: number }
    const slice = open.slice(current * size, current * size + size).map((row) => fraudAlert(row))
    return { status: 200, body: json(page(slice, { page: current, size, totalElements: open.length })) }
  })
  return { calls, patches: () => calls.filter((c) => c.method === 'patch') }
}

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

const list = () => screen.findByRole('list', { name: 'Open fraud alerts' })

describe('FraudAlertsPage', () => {
  it('lists open alerts with rule, written severity, plain description and transaction', async () => {
    server([
      { rule: 'DUPLICATE_TRANSACTION', severity: 'HIGH', description: '<b>Same</b> amount twice', transactionId: 41 },
      { rule: 'UNSEEN_COUNTRY', severity: 'MEDIUM' },
    ])
    renderWithProviders(<FraudAlertsPage />)
    const items = within(await list()).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    const first = items[0] as HTMLElement
    expect(within(first).getByRole('heading', { name: 'Duplicate transaction' })).toBeInTheDocument()
    expect(within(first).getByText('High severity')).toBeInTheDocument()
    expect(within(first).getByText('<b>Same</b> amount twice')).toBeInTheDocument()
    expect(first.querySelector('b')).toBeNull()
    expect(within(first).getByText(/15 Mar 2026, 12:00 · Transaction #41/)).toBeInTheDocument()
  })

  it('acknowledges an alert, removes it and says so', async () => {
    const s = server([{ rule: 'ABNORMAL_AMOUNT', transactionId: 10 }, { rule: 'UNSEEN_COUNTRY', transactionId: 11 }])
    renderWithProviders(<FraudAlertsPage />)
    fireEvent.click(within(await list()).getByRole('button', { name: 'Acknowledge Unusually large amount alert for transaction 10' }))
    expect(await screen.findByText('Unusually large amount alert acknowledged.')).toBeInTheDocument()
    expect(s.patches()[0]?.url).toBe('/fraud/alerts/1/acknowledge')
    await waitFor(async () => expect(within(await list()).getAllByRole('listitem')).toHaveLength(1))
  })

  it('marks only the clicked alert busy and blocks the others', async () => {
    const gate = deferred()
    server([{ transactionId: 10 }, { transactionId: 11 }], {
      ack: async () => {
        await gate.promise
        return { status: 200, body: json(fraudAlert({ acknowledged: true })) }
      },
    })
    renderWithProviders(<FraudAlertsPage />)
    const [first, second] = within(await list()).getAllByRole('button')
    fireEvent.click(first as HTMLElement)
    await waitFor(() => expect(first).toHaveAttribute('aria-busy', 'true'))
    expect(second).toBeDisabled()
    gate.resolve()
  })

  it('shows the error when acknowledging fails', async () => {
    server([{ transactionId: 10 }], { ack: () => ({ status: 404, body: '{"status":404,"detail":"Fraud alert not found"}' }) })
    renderWithProviders(<FraudAlertsPage />)
    fireEvent.click(within(await list()).getByRole('button'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Fraud alert not found')
  })

  it('shows an empty state', async () => {
    server([])
    renderWithProviders(<FraudAlertsPage />)
    expect(await screen.findByText('No open alerts')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    let fail = true
    server([{}], { list: () => (fail ? { status: 500, body: '{"status":500}' } : null) })
    renderWithProviders(<FraudAlertsPage />)
    const alert = (await screen.findByText('Could not load fraud alerts')).closest('[role="alert"]') as HTMLElement
    fail = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await list()).toBeInTheDocument()
  })

  it('pages with the backend page number, and steps back when a last page empties', async () => {
    const s = server(Array.from({ length: 21 }, (_, i) => ({ transactionId: 100 + i })))
    renderWithProviders(<FraudAlertsPage />)
    await list()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(s.calls.at(-1)?.params).toEqual({ page: 1, size: 20 })
    fireEvent.click(within(await list()).getByRole('button', { name: /transaction 120/ }))
    await waitFor(() => expect(screen.queryByText('Page 2 of 2')).not.toBeInTheDocument())
    await waitFor(async () => expect(within(await list()).getAllByRole('listitem')).toHaveLength(20))
  })
})
