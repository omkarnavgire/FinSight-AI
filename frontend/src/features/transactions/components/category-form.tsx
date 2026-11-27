import { type FormEvent, useState } from 'react'
import { toApiError } from '@/shared/api/api-error'
import { CATEGORIES, CATEGORY_LABELS } from '@/shared/domain/categories'
import type { Category, TransactionDto } from '@/shared/types/api'
import { Alert } from '@/shared/ui/alert'
import { Button } from '@/shared/ui/button'
import { SelectField } from '@/shared/ui/select-field'
import { useRecategoriseTransaction } from '../hooks/use-transaction-mutations'

const OPTIONS = CATEGORIES.map((category) => ({ value: category, label: CATEGORY_LABELS[category] }))

export interface CategoryFormProps {
  transaction: TransactionDto
  onSaved: (transaction: TransactionDto) => void
  onCancel: () => void
}

/** Changes only the category (`PATCH …/category`); the backend marks it as set by the user. */
export function CategoryForm({ transaction, onSaved, onCancel }: CategoryFormProps) {
  const mutation = useRecategoriseTransaction()
  const [category, setCategory] = useState<Category | ''>(transaction.category)

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (category === '') return
    mutation.mutate({ id: transaction.id, category }, { onSuccess: onSaved })
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      {mutation.error === null ? null : <Alert tone="error">{toApiError(mutation.error).message}</Alert>}
      <SelectField<Category> label="Category" required options={OPTIONS} value={category} onChange={setCategory} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" busy={mutation.isPending}>Save category</Button>
      </div>
    </form>
  )
}
