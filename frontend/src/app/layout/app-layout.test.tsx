import { afterEach, describe, expect, it } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import { NAV_ITEMS } from './nav-items'
import { renderApp, setupAppBackend } from '@/test/render-app'

afterEach(() => {
  Reflect.deleteProperty(navigator, 'locks')
})

describe('AppLayout', () => {
  it('lists every module and marks the current page', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/budgets')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(NAV_ITEMS.map((item) => item.label))
    expect(within(nav).getByRole('link', { name: 'Budgets' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
  })

  it('has a skip link to the main content', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/dashboard')
    expect(await screen.findByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main-content')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
  })

  it('shows the signed-in email from the token', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/dashboard')
    expect(await screen.findByTitle('demo@finsight.dev')).toBeInTheDocument()
  })

  it('opens the mobile menu and closes it after navigating', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/dashboard')
    const menuButton = await screen.findByRole('button', { name: 'Open menu' })
    expect(menuButton).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(menuButton)
    const menu = screen.getByRole('dialog', { name: 'Menu' })
    expect(menuButton).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(within(menu).getByRole('link', { name: 'Cash Flow' }))
    expect(await screen.findByRole('heading', { name: 'Cash Flow', level: 1 })).toBeInTheDocument()
    expect(menu).not.toHaveAttribute('open')
    expect(within(menu).queryByRole('link')).not.toBeInTheDocument()
  })

  it('shows the one-tab warning without Web Locks', async () => {
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/dashboard')
    expect(await screen.findByRole('note')).toHaveTextContent('Use FinSight in one tab')
  })

  it('hides the warning when Web Locks exists', async () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: (_n: string, _o: unknown, callback: () => Promise<string>) => callback() },
      configurable: true,
    })
    setupAppBackend(true, () => ({ status: 200 }))
    renderApp('/dashboard')
    await screen.findByRole('heading', { name: 'Dashboard', level: 1 })
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})
