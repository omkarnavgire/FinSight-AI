/**
 * Client-side checks for the auth forms (T-65). They mirror the backend's Bean Validation on
 * `LoginRequest` and `RegisterRequest`; the backend still decides.
 */

export interface LoginForm {
  email: string
  password: string
}

export interface RegisterForm {
  fullName: string
  email: string
  password: string
}

export type FieldErrors<K extends string> = Partial<Record<K, string>>

export const EMAIL_MAX = 255
export const PASSWORD_MIN = 8
export const PASSWORD_MAX = 72
export const FULL_NAME_MAX = 100

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function checkEmail(email: string): string | undefined {
  const value = email.trim()
  if (value === '') return 'Enter your email.'
  if (value.length > EMAIL_MAX) return `Use at most ${EMAIL_MAX} characters.`
  if (!EMAIL_PATTERN.test(value)) return 'Enter a valid email address, like name@example.com.'
  return undefined
}

function compact<K extends string>(errors: Record<K, string | undefined>): FieldErrors<K> {
  return Object.fromEntries(Object.entries(errors).filter(([, message]) => message !== undefined)) as FieldErrors<K>
}

/** Checks the login form. An empty result means it may be sent. */
export function validateLogin(form: LoginForm): FieldErrors<keyof LoginForm> {
  return compact({
    email: checkEmail(form.email),
    password: form.password === '' ? 'Enter your password.' : undefined,
  })
}

/** Checks the register form with the backend's length rules. */
export function validateRegister(form: RegisterForm): FieldErrors<keyof RegisterForm> {
  const name = form.fullName.trim()
  let password: string | undefined
  if (form.password.length < PASSWORD_MIN) password = `Use at least ${PASSWORD_MIN} characters.`
  else if (form.password.length > PASSWORD_MAX) password = `Use at most ${PASSWORD_MAX} characters.`
  return compact({
    fullName:
      name === '' ? 'Enter your full name.' : name.length > FULL_NAME_MAX ? `Use at most ${FULL_NAME_MAX} characters.` : undefined,
    email: checkEmail(form.email),
    password,
  })
}

/** True when there are no errors. */
export function isValid(errors: FieldErrors<string>): boolean {
  return Object.keys(errors).length === 0
}
