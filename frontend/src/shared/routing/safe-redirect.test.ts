import { describe, expect, it } from 'vitest'
import { DEFAULT_AFTER_LOGIN, loginRedirectState, redirectTargetFromState, safeRedirect } from './safe-redirect'

describe('safeRedirect', () => {
  it('keeps same-origin paths with query and hash', () => {
    expect(safeRedirect('/budgets')).toBe('/budgets')
    expect(safeRedirect('/transactions?category=DINING&page=2#list')).toBe('/transactions?category=DINING&page=2#list')
  })

  it('normalises dot segments inside the app', () => {
    expect(safeRedirect('/accounts/../budgets')).toBe('/budgets')
  })

  it('blocks open redirects', () => {
    const unsafe = [
      'https://evil.example',
      '//evil.example',
      '///evil.example',
      '/\\evil.example',
      '\\\\evil.example',
      'javascript:alert(1)',
      'data:text/html,hi',
      'budgets',
      ' /budgets',
      '/budgets\n',
      '/a\tb',
      '',
    ]
    for (const target of unsafe) {
      expect(safeRedirect(target), JSON.stringify(target)).toBe(DEFAULT_AFTER_LOGIN)
    }
  })

  it('never returns to the auth pages', () => {
    expect(safeRedirect('/login')).toBe(DEFAULT_AFTER_LOGIN)
    expect(safeRedirect('/register?x=1')).toBe(DEFAULT_AFTER_LOGIN)
  })

  it('refuses non-strings and very long values', () => {
    for (const target of [null, undefined, 42, {}, ['/budgets'], `/${'a'.repeat(2048)}`]) {
      expect(safeRedirect(target)).toBe(DEFAULT_AFTER_LOGIN)
    }
  })

  it('uses the given fallback', () => {
    expect(safeRedirect('//evil.example', '/accounts')).toBe('/accounts')
  })
})

describe('router state helpers', () => {
  it('round-trips a location through router state', () => {
    const state = loginRedirectState({ pathname: '/cashflow', search: '?horizon=60', hash: '' })
    expect(state).toEqual({ from: '/cashflow?horizon=60' })
    expect(redirectTargetFromState(state)).toBe('/cashflow?horizon=60')
  })

  it('falls back for missing or hostile state', () => {
    expect(redirectTargetFromState(null)).toBe(DEFAULT_AFTER_LOGIN)
    expect(redirectTargetFromState('x')).toBe(DEFAULT_AFTER_LOGIN)
    expect(redirectTargetFromState({ from: 'https://evil.example' })).toBe(DEFAULT_AFTER_LOGIN)
  })
})
