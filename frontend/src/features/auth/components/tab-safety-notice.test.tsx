import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { TabSafetyNotice } from './tab-safety-notice'
import { resetSessionRestore } from '../lib/restore-session'
import { refreshClient } from '@/shared/api/refresh'
import { clearAccessToken } from '@/shared/api/auth-token'
import { mockAdapter } from '@/test/mock-adapter'
import { renderWithProviders } from '@/test/render-with-providers'

beforeEach(() => {
  clearAccessToken()
  resetSessionRestore()
  refreshClient.defaults.adapter = mockAdapter(() => ({ status: 401 }))
})

afterEach(() => {
  Reflect.deleteProperty(navigator, 'locks')
})

describe('TabSafetyNotice', () => {
  it('warns when Web Locks is missing', async () => {
    renderWithProviders(<TabSafetyNotice />)
    const note = await screen.findByRole('note')
    expect(note).toHaveTextContent('Use FinSight in one tab')
    expect(note).toHaveTextContent('signed out everywhere')
  })

  it('stays hidden when tabs can be coordinated', () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: (_n: string, _o: unknown, callback: () => Promise<string>) => callback() },
      configurable: true,
    })
    renderWithProviders(<TabSafetyNotice />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})
