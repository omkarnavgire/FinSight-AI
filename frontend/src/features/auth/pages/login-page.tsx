import { type FormEvent, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { toApiError } from '@/shared/api/api-error'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/text-field'
import { AuthCard } from '../components/auth-card'
import type { SessionEndReason } from '../context/auth-context'
import { useAuth } from '../hooks/use-auth'
import { useLogin } from '../hooks/use-login'
import { type FieldErrors, isValid, type LoginForm, validateLogin } from '../lib/validate-auth-form'

const END_MESSAGES: Readonly<Record<SessionEndReason, string>> = {
  logout: 'You are signed out.',
  expired: 'Your session ended. Please log in again.',
  'remote-logout': 'You signed out in another tab.',
}

const WRONG_CREDENTIALS = 'Email or password is wrong.'

/** Login form (T-65). The password is cleared after a failed attempt and never stored. */
export function LoginPage() {
  const { endReason } = useAuth()
  const location = useLocation()
  const mutation = useLogin()
  const [form, setForm] = useState<LoginForm>({ email: '', password: '' })
  const [errors, setErrors] = useState<FieldErrors<keyof LoginForm>>({})

  const apiError = mutation.error === null ? null : toApiError(mutation.error)
  const message = apiError === null ? null : apiError.status === 401 ? WRONG_CREDENTIALS : apiError.message

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const found = validateLogin(form)
    setErrors(found)
    if (!isValid(found)) return
    mutation.mutate(
      { email: form.email.trim(), password: form.password },
      { onError: () => setForm((current) => ({ ...current, password: '' })) },
    )
  }

  return (
    <AuthCard
      title="Log in"
      footer={
        <>
          No account yet?{' '}
          <Link to="/register" state={location.state} className="font-medium text-sky-300 underline">
            Register
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {endReason !== null && message === null ? <Alert tone="info">{END_MESSAGES[endReason]}</Alert> : null}
        {message === null ? null : <Alert tone="error">{message}</Alert>}
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="username"
          required
          value={form.email}
          onChange={(email) => setForm((current) => ({ ...current, email }))}
          error={errors.email ?? apiError?.fieldErrors.email}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          required
          value={form.password}
          onChange={(password) => setForm((current) => ({ ...current, password }))}
          error={errors.password ?? apiError?.fieldErrors.password}
        />
        <Button type="submit" busy={mutation.isPending}>
          Log in
        </Button>
      </form>
    </AuthCard>
  )
}
