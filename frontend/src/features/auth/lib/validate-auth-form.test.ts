import { describe, expect, it } from 'vitest'
import { isValid, validateLogin, validateRegister } from './validate-auth-form'

describe('validateLogin', () => {
  it('accepts a filled form', () => {
    expect(validateLogin({ email: ' demo@finsight.dev ', password: 'x' })).toEqual({})
  })

  it('needs both fields', () => {
    expect(validateLogin({ email: '', password: '' })).toEqual({
      email: 'Enter your email.',
      password: 'Enter your password.',
    })
  })

  it('checks the email shape', () => {
    for (const email of ['demo', 'demo@', '@finsight.dev', 'de mo@finsight.dev', 'demo@finsight']) {
      expect(validateLogin({ email, password: 'x' }).email, email).toMatch(/valid email/)
    }
  })
})

describe('validateRegister', () => {
  const good = { fullName: 'Demo User', email: 'demo@finsight.dev', password: '12345678' }

  it('accepts the backend boundaries', () => {
    expect(isValid(validateRegister(good))).toBe(true)
    expect(isValid(validateRegister({ ...good, password: 'p'.repeat(72), fullName: 'n'.repeat(100) }))).toBe(true)
  })

  it('refuses passwords outside 8–72 characters', () => {
    expect(validateRegister({ ...good, password: '1234567' }).password).toBe('Use at least 8 characters.')
    expect(validateRegister({ ...good, password: 'p'.repeat(73) }).password).toBe('Use at most 72 characters.')
  })

  it('refuses a missing or long name', () => {
    expect(validateRegister({ ...good, fullName: '   ' }).fullName).toBe('Enter your full name.')
    expect(validateRegister({ ...good, fullName: 'n'.repeat(101) }).fullName).toBe('Use at most 100 characters.')
  })

  it('refuses an email over 255 characters', () => {
    const email = `${'a'.repeat(250)}@x.com`
    expect(validateRegister({ ...good, email }).email).toBe('Use at most 255 characters.')
  })
})
