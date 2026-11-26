import { type FormEvent, useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import { CATEGORY_LABELS } from '@/shared/domain/categories'
import type { BudgetDto, Category } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { MoneyInput } from '@/shared/ui/money-input'
import { SelectField } from '@/shared/ui/select-field'
import { useCreateBudget, useUpdateBudget } from '../hooks/use-budget-mutations'
import { BUDGET_CATEGORIES, type BudgetFormErrors, checkMonthlyLimit, validateCreateBudget } from '../lib/validate-budget-form'

const OPTIONS = BUDGET_CATEGORIES.map((category) => ({ value: category, label: CATEGORY_LABELS[category] }))

export interface BudgetFormProps {
  /** Present in edit mode: only the limit can change. */
  editing?: BudgetDto
  /** Currency label for the limit hint, when every account uses one currency. */
  currency?: string
  onSaved: (budget: BudgetDto) => void
  onCancel: () => void
}

/** Create a budget for this month, or change a budget's monthly limit (T-70). */
export function BudgetForm({ editing, currency, onSaved, onCancel }: BudgetFormProps) {
  const create = useCreateBudget()
  const update = useUpdateBudget()
  const mutation = editing === undefined ? create : update
  const [category, setCategory] = useState<Category | ''>(editing?.category ?? '')
  const [limit, setLimit] = useState<string>(editing?.monthlyLimit ?? '')
  const [errors, setErrors] = useState<BudgetFormErrors>({})
  const apiError = mutation.error === null ? null : toApiError(mutation.error)

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (editing === undefined) {
      const result = validateCreateBudget(category, limit)
      setErrors(result.ok ? {} : result.errors)
      if (result.ok) create.mutate(result.request, { onSuccess: onSaved })
      return
    }
    const checked = checkMonthlyLimit(limit)
    setErrors(checked.ok ? {} : { monthlyLimit: checked.error })
    if (checked.ok) update.mutate({ id: editing.id, request: { monthlyLimit: checked.value } }, { onSuccess: onSaved })
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      {apiError === null ? null : <Alert tone="error">{apiError.message}</Alert>}
      {editing === undefined ? (
        <SelectField<Category>
          label="Category"
          required
          placeholder="Choose a category"
          hint="The budget is for the current month."
          options={OPTIONS}
          value={category}
          onChange={setCategory}
          error={errors.category ?? apiError?.fieldErrors.category}
        />
      ) : (
        <p className="text-sm text-gray-300">Category: {CATEGORY_LABELS[editing.category]}</p>
      )}
      <MoneyInput
        label="Monthly limit"
        required
        hint={currency === undefined ? 'A positive amount.' : `A positive amount in ${currency}.`}
        value={limit}
        onChange={setLimit}
        error={errors.monthlyLimit ?? apiError?.fieldErrors.monthlyLimit}
      />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" busy={mutation.isPending}>{editing === undefined ? 'Create budget' : 'Save limit'}</Button>
      </div>
    </form>
  )
}
