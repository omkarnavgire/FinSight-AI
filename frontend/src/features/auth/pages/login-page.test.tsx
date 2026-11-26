import { describe, expect, it } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { setupAuthBackend, renderAuthRoutes } from './auth-pages.test-utils'
import { getAccessToken } from '@/shared/api/auth-token'
import { tokenBody } from '@/test/mock-adapter'
import { userJwt } from '@/test/fake-jwt'

const TOKEN = userJwt(7, 'demo@finsight.dev')

async function fillAndSubmit(email: string, password: string): Promise<void> {
  fireEvent.change(await screen.findByLabelText(/^Email/), { target: { value: email } })
  fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('LoginPage', () => {
  it('shows field errors and sends nothing for an empty form', async () => {
    const backend = setupAuthBackend(() => ({ status: 200, body: tokenBody(TOKEN) }))
    renderAuthRoutes('/login')
    fireEvent.click(await screen.findByRole('button', { name: 'Log in' }))
    expect(screen.getByLabelText(/^Email/)).toHaveAccessibleDescription('Enter your email.')
    expect(screen.getByLabelText(/^Password/)).toHaveAccessibleDescription('Enter your password.')
    expect(backend.calls).toHaveLength(0)
  })

  it('logs in, keeps the token in memory and opens the dashboard', async () => {
    const backend = setupAuthBackend(() => ({ status: 200, body: tokenBody(TOKEN) }))
    renderAuthRoutes('/login')
    await fillAndSubmit('  demo@finsight.dev ', 'Correct#123')

    expect(await screen.findByText('Dashboard page')).toBeInTheDocument()
    expect(getAccessToken()).toBe(TOKEN)
    expect(backend.calls[0]?.url).toBe('/auth/login')
    expect(backend.calls[0]?.data).toBe('{"email":"demo@finsight.dev","password":"Correct#123"}')
  })

  it('returns to the saved page after login', async () => {
    setupAuthBackend(() => ({ status: 200, body: tokenBody(TOKEN) }))
    renderAuthRoutes({ pathname: '/login', state: { from: '/budgets' } })
    await fillAndSubmit('demo@finsight.dev', 'Correct#123')
    expect(await screen.findByText('Budgets page')).toBeInTheDocument()
  })

  it('says the credentials are wrong on 401, clears the password and does not refresh', async () => {
    const backend = setupAuthBackend(() => ({ status: 401, body: '{"status":401,"detail":"Invalid credentials"}' }))
    renderAuthRoutes('/login')
    await fillAndSubmit('demo@finsight.dev', 'Wrong#123')

    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is wrong.')
    expect(screen.getByLabelText(/^Password/)).toHaveValue('')
    expect(backend.refreshCalls()).toBe(1) // the boot restore only
    expect(getAccessToken()).toBeNull()
  })

  it('shows the wait time on 429', async () => {
    setupAuthBackend(() => ({ status: 429, body: '{"status":429}', headers: { 'Retry-After': '840' } }))
    renderAuthRoutes('/login')
    await fillAndSubmit('demo@finsight.dev', 'Any#1234')
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 14 minutes.')
  })

  it('links to register, keeping the saved page', async () => {
    setupAuthBackend(() => ({ status: 200 }))
    renderAuthRoutes({ pathname: '/login', state: { from: '/budgets' } })
    fireEvent.click(await screen.findByRole('link', { name: 'Register' }))
    expect(await screen.findByRole('heading', { name: 'Create your account' })).toBeInTheDocument()
  })
})
