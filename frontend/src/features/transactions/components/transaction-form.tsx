import { type FormEvent, useId, useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import { CATEGORIES, CATEGORY_LABELS } from '@/shared/domain/categories'
import type { AccountDto, Category, TransactionDto } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { MoneyInput } from '@/shared/ui/money-input'
import { SegmentedControl } from '@/shared/ui/segmented-control'
import { SelectField } from '@/shared/ui/select-field'
import { TextField } from '@/shared/ui/text-field'
import { useCreateTransaction, useUpdateTransaction } from '../hooks/use-transaction-mutations'
import { COUNTRY_SUGGESTIONS } from '../lib/country-suggestions'
import {
  type AmountKind,
  DESCRIPTION_MAX,
  emptyTransactionForm,
  formFromTransaction,
  type TransactionForm as FormState,
  type TransactionFormErrors,
  validateCreateForm,
  validateEditForm,
} from '../lib/transaction-form'

const CATEGORY_OPTIONS = CATEGORIES.map((category) => ({ value: category, label: CATEGORY_LABELS[category] }))
const KIND_OPTIONS = [
  { value: 'EXPENSE', label: 'Expense' },
  { value: 'INCOME', label: 'Income' },
] as const

export interface TransactionFormProps {
  /** Active accounts the user may add to (create only). */
  accounts: readonly AccountDto[]
  /** Present in edit mode. Account and country cannot be changed (backend `UpdateTransactionRequest`). */
  editing?: TransactionDto
  today: string
  onSaved: (transaction: TransactionDto) => void
  onCancel: () => void
}

/** Add or edit a transaction (T-69). The amount is typed positive; Expense/Income sets its sign. */
export function TransactionForm({ accounts, editing, today, onSaved, onCancel }: TransactionFormProps) {
  const create = useCreateTransaction()
  const update = useUpdateTransaction()
  const mutation = editing === undefined ? create : update
  const [form, setForm] = useState<FormState>(() => (editing === undefined ? emptyTransactionForm(today) : formFromTransaction(editing)))
  const [errors, setErrors] = useState<TransactionFormErrors>({})
  const countryListId = useId()
  const apiError = mutation.error === null ? null : toApiError(mutation.error)
  const fieldError = (field: keyof FormState) => errors[field] ?? apiError?.fieldErrors[field]
  const set = (patch: Partial<FormState>) => setForm((current) => ({ ...current, ...patch }))
  const accountOptions = accounts.map((account) => ({ value: String(account.id), label: `${account.name} (${account.currency})` }))
  const currency = accounts.find((account) => String(account.id) === form.accountId)?.currency

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (editing === undefined) {
      const result = validateCreateForm(form)
      setErrors(result.ok ? {} : result.errors)
      if (result.ok) create.mutate(result.request, { onSuccess: onSaved })
      return
    }
    const result = validateEditForm(form)
    setErrors(result.ok ? {} : result.errors)
    if (result.ok) update.mutate({ id: editing.id, request: result.request }, { onSuccess: onSaved })
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      {apiError === null ? null : <Alert tone="error">{apiError.message}</Alert>}
      {editing === undefined ? (
        <SelectField label="Account" required placeholder="Choose an account" options={accountOptions} value={form.accountId} onChange={(accountId) => set({ accountId })} error={fieldError('accountId')} />
      ) : null}
      <SegmentedControl<AmountKind> legend="Type" options={KIND_OPTIONS} value={form.kind} onChange={(kind) => set({ kind })} />
      <MoneyInput
        label="Amount"
        required
        hint={`${form.kind === 'EXPENSE' ? 'Money out' : 'Money in'}${currency === undefined ? '' : `, in ${currency}`}. Type it without a sign.`}
        value={form.amount}
        onChange={(amount) => set({ amount })}
        error={fieldError('amount')}
      />
      <TextField label="Date" type="date" required value={form.transactionDate} onChange={(transactionDate) => set({ transactionDate })} error={fieldError('transactionDate')} />
      <SelectField<Category>
        label="Category"
        required={editing !== undefined}
        placeholder={editing === undefined ? 'Automatic' : undefined}
        options={CATEGORY_OPTIONS}
        value={form.category}
        onChange={(category) => set({ category })}
        error={fieldError('category')}
      />
      <TextField label="Description" maxLength={DESCRIPTION_MAX} value={form.description} onChange={(description) => set({ description })} error={fieldError('description')} />
      {editing === undefined ? (
        <div>
          <TextField
            label="Country"
            required
            maxLength={2}
            autoComplete="off"
            hint="2-letter code where the payment happened, like US."
            value={form.countryCode}
            onChange={(countryCode) => set({ countryCode: countryCode.toUpperCase() })}
            error={fieldError('countryCode')}
            list={countryListId}
          />
          <datalist id={countryListId}>
            {COUNTRY_SUGGESTIONS.map((country) => (
              <option key={country.code} value={country.code}>{country.name}</option>
            ))}
          </datalist>
        </div>
      ) : null}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" busy={mutation.isPending}>{editing === undefined ? 'Add transaction' : 'Save changes'}</Button>
      </div>
    </form>
  )
}
