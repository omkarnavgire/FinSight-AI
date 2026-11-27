import { type FormEvent, useState } from 'react'
import { CATEGORIES, CATEGORY_LABELS } from '@/shared/domain/categories'
import { describeMoneyInputError, parseMoneyInput } from '@/shared/money/decimal'
import type { AccountDto, Category, TransactionFilter } from '@/shared/types/api'
import { Button } from '@/shared/ui/button'
import { MoneyInput } from '@/shared/ui/money-input'
import { SelectField } from '@/shared/ui/select-field'
import { TextField } from '@/shared/ui/text-field'
import { filterRangeErrors } from '../lib/filter-params'

const CATEGORY_OPTIONS = CATEGORIES.map((category) => ({ value: category, label: CATEGORY_LABELS[category] }))

export interface TransactionFiltersProps {
  value: TransactionFilter
  accounts: readonly AccountDto[]
  onApply: (filter: TransactionFilter) => void
}

interface Draft {
  accountId: string
  from: string
  to: string
  category: Category | ''
  minAmount: string
  maxAmount: string
}

function toDraft(filter: TransactionFilter): Draft {
  return {
    accountId: filter.accountId === undefined ? '' : String(filter.accountId),
    from: filter.from ?? '',
    to: filter.to ?? '',
    category: filter.category ?? '',
    minAmount: filter.minAmount ?? '',
    maxAmount: filter.maxAmount ?? '',
  }
}

/** Filter form. Amount bounds compare signed amounts: expenses are negative. */
export function TransactionFilters({ value, accounts, onApply }: TransactionFiltersProps) {
  const [draft, setDraft] = useState<Draft>(() => toDraft(value))
  const [errors, setErrors] = useState<Partial<Record<keyof Draft, string>>>({})
  const accountOptions = accounts.map((account) => ({ value: String(account.id), label: `${account.name} (${account.currency})` }))
  const set = (patch: Partial<Draft>) => setDraft((current) => ({ ...current, ...patch }))

  function apply(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const found: Partial<Record<keyof Draft, string>> = {}
    const filter: TransactionFilter = {}
    if (draft.accountId !== '') filter.accountId = Number(draft.accountId)
    if (draft.from !== '') filter.from = draft.from
    if (draft.to !== '') filter.to = draft.to
    if (draft.category !== '') filter.category = draft.category
    for (const key of ['minAmount', 'maxAmount'] as const) {
      if (draft[key].trim() === '') continue
      const parsed = parseMoneyInput(draft[key], { allowNegative: true })
      if (parsed.ok) filter[key] = parsed.value
      else found[key] = describeMoneyInputError(parsed.error)
    }
    Object.assign(found, filterRangeErrors(filter))
    setErrors(found)
    if (Object.keys(found).length === 0) onApply(filter)
  }

  function clear(): void {
    setDraft(toDraft({}))
    setErrors({})
    onApply({})
  }

  return (
    <form noValidate onSubmit={apply} aria-label="Filter transactions" className="grid grid-cols-1 gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4 sm:grid-cols-2 lg:grid-cols-3">
      <SelectField label="Account" placeholder="All accounts" options={accountOptions} value={draft.accountId} onChange={(accountId) => set({ accountId })} />
      <SelectField<Category> label="Category" placeholder="All categories" options={CATEGORY_OPTIONS} value={draft.category} onChange={(category) => set({ category })} />
      <TextField label="From" type="date" value={draft.from} onChange={(from) => set({ from })} />
      <TextField label="To" type="date" value={draft.to} onChange={(to) => set({ to })} error={errors.to} />
      <MoneyInput label="Minimum amount" hint="Signed: expenses are negative." value={draft.minAmount} onChange={(minAmount) => set({ minAmount })} error={errors.minAmount} />
      <MoneyInput label="Maximum amount" hint="Signed: expenses are negative." value={draft.maxAmount} onChange={(maxAmount) => set({ maxAmount })} error={errors.maxAmount} />
      <div className="flex gap-2 sm:col-span-2 lg:col-span-3">
        <Button type="submit">Apply filters</Button>
        <Button variant="secondary" onClick={clear}>
          Clear filters
        </Button>
      </div>
    </form>
  )
}
