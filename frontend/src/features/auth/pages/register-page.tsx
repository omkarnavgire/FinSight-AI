import { type FormEvent, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { toApiError } from '@/shared/api/api-error'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/text-field'
import { AuthCard } from '../components/auth-card'
import { useRegister } from '../hooks/use-register'
import {
  EMAIL_MAX,
  FULL_NAME_MAX,
  type FieldErrors,
  isValid,
  PASSWORD_MAX,
  PASSWORD_MIN,
  type RegisterForm,
  validateRegister,
} from '../lib/validate-auth-form'

/** Register form (T-65). On success the user is signed in straight away. */
export function RegisterPage() {
  const location = useLocation()
  const mutation = useRegister()
  const [form, setForm] = useState<RegisterForm>({ fullName: '', email: '', password: '' })
  const [errors, setErrors] = useState<FieldErrors<keyof RegisterForm>>({})

  const apiError = mutation.error === null ? null : toApiError(mutation.error)
  const fieldError = (field: keyof RegisterForm): string | undefined => errors[field] ?? apiError?.fieldErrors[field]

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const found = validateRegister(form)
    setErrors(found)
    if (!isValid(found)) return
    mutation.mutate(
      { fullName: form.fullName.trim(), email: form.email.trim(), password: form.password },
      { onError: () => setForm((current) => ({ ...current, password: '' })) },
    )
  }

  return (
    <AuthCard
      title="Create your account"
      footer={
        <>
          Already registered?{' '}
          <Link to="/login" state={location.state} className="font-medium text-sky-300 underline">
            Log in
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {apiError === null ? null : <Alert tone="error">{apiError.message}</Alert>}
        <TextField
          label="Full name"
          name="fullName"
          autoComplete="name"
          required
          maxLength={FULL_NAME_MAX}
          value={form.fullName}
          onChange={(fullName) => setForm((current) => ({ ...current, fullName }))}
          error={fieldError('fullName')}
        />
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          maxLength={EMAIL_MAX}
          value={form.email}
          onChange={(email) => setForm((current) => ({ ...current, email }))}
          error={fieldError('email')}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="new-password"
          required
          hint={`${PASSWORD_MIN} to ${PASSWORD_MAX} characters.`}
          value={form.password}
          onChange={(password) => setForm((current) => ({ ...current, password }))}
          error={fieldError('password')}
        />
        <Button type="submit" busy={mutation.isPending}>
          Create account
        </Button>
      </form>
    </AuthCard>
  )
}
