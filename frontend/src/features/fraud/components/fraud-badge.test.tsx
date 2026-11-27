import { describe, expect, it } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import { focusManager } from '@tanstack/react-query'
import { FraudBadge } from './fraud-badge'
import { apiClient } from '@/shared/api/client'
import { refreshClient } from '@/shared/api/refresh'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { fraudAlert, json, page } from '@/test/fixtures'
import { renderWithProviders } from '@/test/render-with-providers'

function serve(reply: () => MockReply) {
  let calls = 0
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
  apiClient.defaults.adapter = mockAdapter(() => {
    calls++
    return reply()
  })
  return () => calls
}

const counted = (total: number): MockReply => ({ status: 200, body: json(page(total === 0 ? [] : [fraudAlert()], { size: 1, totalElements: total })) })

describe('FraudBadge', () => {
  it('shows the count with a full screen-reader label', async () => {
    serve(() => counted(3))
    renderWithProviders(<FraudBadge />)
    expect(await screen.findByText('3 unacknowledged fraud alerts')).toHaveClass('sr-only')
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('uses the singular for one alert', async () => {
    serve(() => counted(1))
    renderWithProviders(<FraudBadge />)
    expect(await screen.findByText('1 unacknowledged fraud alert')).toBeInTheDocument()
  })

  it('caps the visible number at 99+', async () => {
    serve(() => counted(250))
    renderWithProviders(<FraudBadge />)
    expect(await screen.findByText('99+')).toBeInTheDocument()
    expect(screen.getByText('250 unacknowledged fraud alerts')).toBeInTheDocument()
  })

  it('is hidden at zero', async () => {
    const calls = serve(() => counted(0))
    const { container } = renderWithProviders(<FraudBadge />)
    await waitFor(() => expect(calls()).toBe(1))
    expect(container).toBeEmptyDOMElement()
  })

  it('is hidden on error, never showing a wrong number', async () => {
    const calls = serve(() => ({ status: 500, body: '{"status":500}' }))
    const { container } = renderWithProviders(<FraudBadge />)
    await waitFor(() => expect(calls()).toBe(1))
    expect(container).toBeEmptyDOMElement()
  })

  it('rechecks when fraud alerts are invalidated (e.g. after a new transaction)', async () => {
    let total = 1
    const calls = serve(() => counted(total))
    const { queryClient } = renderWithProviders(<FraudBadge />)
    await screen.findByText('1 unacknowledged fraud alert')
    total = 2
    await act(() => queryClient.invalidateQueries({ queryKey: ['fraud-alerts'] }))
    expect(await screen.findByText('2 unacknowledged fraud alerts')).toBeInTheDocument()
    expect(calls()).toBe(2)
  })

  it('rechecks when the window regains focus', async () => {
    const calls = serve(() => counted(1))
    renderWithProviders(<FraudBadge />)
    await screen.findByText('1 unacknowledged fraud alert')
    act(() => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
    })
    await waitFor(() => expect(calls()).toBe(2))
    focusManager.setFocused(undefined)
  })
})
