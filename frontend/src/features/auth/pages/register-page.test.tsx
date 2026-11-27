import { describe, expect, it } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { setupAuthBackend, renderAuthRoutes } from './auth-pages.test-utils'
import { getAccessToken } from '@/shared/api/auth-token'
import { tokenBody } from '@/test/mock-adapter'
import { userJwt } from '@/test/fake-jwt'

const TOKEN = userJwt(11, 'new@finsight.dev')

async function fill(fullName: string, email: string, password: string): Promise<void> {
  fireEvent.change(await screen.findByLabelText(/^Full name/), { target: { value: fullName } })
  fireEvent.change(screen.getByLabelText(/^Email/), { target: { value: email } })
  fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('RegisterPage', () => {
  it('checks lengths before sending', async () => {
    const backend = setupAuthBackend(() => ({ status: 201, body: tokenBody(TOKEN) }))
    renderAuthRoutes('/register')
    await fill('  ', 'new@finsight.dev', 'short')
    expect(screen.getByLabelText(/^Full name/)).toHaveAccessibleDescription('Enter your full name.')
    expect(screen.getByLabelText(/^Password/)).toHaveAccessibleDescription(/Use at least 8 characters\./)
    expect(backend.calls).toHaveLength(0)
  })

  it('registers, signs in and opens the dashboard', async () => {
    const backend = setupAuthBackend(() => ({ status: 201, body: tokenBody(TOKEN) }))
    renderAuthRoutes('/register')
    await fill(' New User ', 'new@finsight.dev', 'Strong#123')

    expect(await screen.findByText('Dashboard page')).toBeInTheDocument()
    expect(getAccessToken()).toBe(TOKEN)
    expect(backend.calls[0]?.url).toBe('/auth/register')
    expect(backend.calls[0]?.data).toBe('{"fullName":"New User","email":"new@finsight.dev","password":"Strong#123"}')
  })

  it('shows the 409 message for a used email', async () => {
    setupAuthBackend(() => ({
      status: 409,
      body: '{"status":409,"detail":"An account with this email already exists"}',
    }))
    renderAuthRoutes('/register')
    await fill('New User', 'demo@finsight.dev', 'Strong#123')
    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists')
    expect(screen.getByLabelText(/^Password/)).toHaveValue('')
  })

  it('puts backend field errors on their fields', async () => {
    const body = JSON.stringify({
      status: 400,
      detail: 'One or more fields are invalid',
      errors: ['email: Email must be a valid email address'],
    })
    setupAuthBackend(() => ({ status: 400, body }))
    renderAuthRoutes('/register')
    await fill('New User', 'odd@finsight.dev', 'Strong#123')
    expect(await screen.findByText('Email must be a valid email address')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Email/)).toHaveAttribute('aria-invalid', 'true')
  })
})
