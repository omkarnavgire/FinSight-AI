/**
 * Transaction form state and checks (T-69). The user types a positive amount and picks Expense or
 * Income; the signed amount (expense = negative) is built exactly with `negateDecimal`.
 */
import { describeMoneyInputError, isNegativeDecimal, isZeroDecimal, negateDecimal, parseMoneyInput } from '@/shared/money/decimal'
import type { Category, CreateTransactionRequest, TransactionDto, UpdateTransactionRequest } from '@/shared/types/api'
import { isIsoDate } from './filter-params'

export const DESCRIPTION_MAX = 255

export type AmountKind = 'EXPENSE' | 'INCOME'

export interface TransactionForm {
  accountId: string
  kind: AmountKind
  amount: string
  transactionDate: string
  category: Category | ''
  description: string
  countryCode: string
}

export type TransactionFormErrors = Partial<Record<keyof TransactionForm, string>>

type Result<T> = { readonly ok: true; readonly request: T } | { readonly ok: false; readonly errors: TransactionFormErrors }

/** An empty create form dated `today`. */
export function emptyTransactionForm(today: string): TransactionForm {
  return { accountId: '', kind: 'EXPENSE', amount: '', transactionDate: today, category: '', description: '', countryCode: '' }
}

/** An edit form filled from an existing transaction (sign → Expense/Income, amount shown positive). */
export function formFromTransaction(transaction: TransactionDto): TransactionForm {
  const negative = isNegativeDecimal(transaction.amount)
  return {
    accountId: String(transaction.accountId),
    kind: negative ? 'EXPENSE' : 'INCOME',
    amount: negative ? negateDecimal(transaction.amount) : transaction.amount,
    transactionDate: transaction.transactionDate,
    category: transaction.category,
    description: transaction.description ?? '',
    countryCode: transaction.countryCode,
  }
}

function checkCommon(form: TransactionForm, errors: TransactionFormErrors) {
  const amount = parseMoneyInput(form.amount)
  if (!amount.ok) errors.amount = describeMoneyInputError(amount.error)
  else if (isZeroDecimal(amount.value)) errors.amount = 'Enter an amount above zero.'
  if (!isIsoDate(form.transactionDate)) errors.transactionDate = 'Choose a date.'
  const description = form.description.trim()
  if (description.length > DESCRIPTION_MAX) errors.description = `Use at most ${DESCRIPTION_MAX} characters.`
  const signed = amount.ok ? (form.kind === 'EXPENSE' ? negateDecimal(amount.value) : amount.value) : null
  return { signed, description: description === '' ? undefined : description }
}

/** Checks a create form and builds the request. */
export function validateCreateForm(form: TransactionForm): Result<CreateTransactionRequest> {
  const errors: TransactionFormErrors = {}
  const { signed, description } = checkCommon(form, errors)
  const accountId = /^[1-9]\d{0,14}$/.test(form.accountId) ? Number(form.accountId) : null
  if (accountId === null) errors.accountId = 'Choose an account.'
  const countryCode = form.countryCode.trim().toUpperCase()
  if (!/^[A-Z]{2}$/.test(countryCode)) errors.countryCode = 'Use a 2-letter country code, like US or GB.'
  if (Object.keys(errors).length > 0 || signed === null || accountId === null) return { ok: false, errors }
  return {
    ok: true,
    request: {
      accountId,
      amount: signed,
      transactionDate: form.transactionDate,
      category: form.category === '' ? undefined : form.category,
      description,
      countryCode,
    },
  }
}

/** Checks an edit form and builds the request (category is required on edit). */
export function validateEditForm(form: TransactionForm): Result<UpdateTransactionRequest> {
  const errors: TransactionFormErrors = {}
  const { signed, description } = checkCommon(form, errors)
  if (form.category === '') errors.category = 'Choose a category.'
  if (Object.keys(errors).length > 0 || signed === null || form.category === '') return { ok: false, errors }
  return { ok: true, request: { amount: signed, transactionDate: form.transactionDate, category: form.category, description } }
}
