import { type FormEvent, useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import { ACCOUNT_TYPE_LABELS, ACCOUNT_TYPES } from '@/shared/domain/categories'
import type { AccountDto, AccountType } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { MoneyInput } from '@/shared/ui/money-input'
import { SelectField } from '@/shared/ui/select-field'
import { TextField } from '@/shared/ui/text-field'
import { useCreateAccount } from '../hooks/use-account-mutations'
import { ACCOUNT_NAME_MAX, type AccountForm, type AccountFormErrors, validateAccountForm } from '../lib/validate-account-form'

const TYPE_OPTIONS = ACCOUNT_TYPES.map((type) => ({ value: type, label: ACCOUNT_TYPE_LABELS[type] }))

export interface CreateAccountFormProps {
  onCreated: (account: AccountDto) => void
  onCancel: () => void
}

/** New-account form (T-68). The opening balance is exact text and may be negative (credit). */
export function CreateAccountForm({ onCreated, onCancel }: CreateAccountFormProps) {
  const mutation = useCreateAccount()
  const [form, setForm] = useState<AccountForm>({ name: '', type: '', openingBalance: '', currency: '' })
  const [errors, setErrors] = useState<AccountFormErrors>({})
  const apiError = mutation.error === null ? null : toApiError(mutation.error)
  const fieldError = (field: keyof AccountForm) => errors[field] ?? apiError?.fieldErrors[field]

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const result = validateAccountForm(form)
    setErrors(result.ok ? {} : result.errors)
    if (result.ok) mutation.mutate(result.request, { onSuccess: onCreated })
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      {apiError === null ? null : <Alert tone="error">{apiError.message}</Alert>}
      <TextField
        label="Name"
        name="name"
        required
        maxLength={ACCOUNT_NAME_MAX}
        value={form.name}
        onChange={(name) => setForm((current) => ({ ...current, name }))}
        error={fieldError('name')}
      />
      <SelectField<AccountType>
        label="Type"
        name="type"
        required
        placeholder="Choose a type"
        options={TYPE_OPTIONS}
        value={form.type}
        onChange={(type) => setForm((current) => ({ ...current, type }))}
        error={fieldError('type')}
      />
      <MoneyInput
        label="Opening balance"
        name="openingBalance"
        required
        hint="Use a minus sign for money owed, for example on a credit card."
        value={form.openingBalance}
        onChange={(openingBalance) => setForm((current) => ({ ...current, openingBalance }))}
        error={fieldError('openingBalance')}
      />
      <TextField
        label="Currency"
        name="currency"
        maxLength={3}
        autoComplete="off"
        hint="3-letter code. Leave blank for USD."
        value={form.currency}
        onChange={(currency) => setForm((current) => ({ ...current, currency: currency.toUpperCase() }))}
        error={fieldError('currency')}
      />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={mutation.isPending}>
          Cancel
        </Button>
        <Button type="submit" busy={mutation.isPending}>
          Create account
        </Button>
      </div>
    </form>
  )
}
