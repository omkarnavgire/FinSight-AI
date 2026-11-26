/**
 * Client checks for the create-account form, mirroring `CreateAccountRequest` on the backend
 * (name ≤ 100, type required, opening balance 13+2 digits of any sign, currency `^[A-Z]{3}$` or blank).
 */
import { describeMoneyInputError, parseMoneyInput } from '@/shared/money/decimal'
import type { AccountType, CreateAccountRequest } from '@/shared/types/api'

export const ACCOUNT_NAME_MAX = 100

export interface AccountForm {
  name: string
  type: AccountType | ''
  openingBalance: string
  currency: string
}

export type AccountFormErrors = Partial<Record<keyof AccountForm, string>>

export type AccountFormResult =
  | { readonly ok: true; readonly request: CreateAccountRequest }
  | { readonly ok: false; readonly errors: AccountFormErrors }

/** Validates the form and builds the request; money stays exact text throughout. */
export function validateAccountForm(form: AccountForm): AccountFormResult {
  const errors: AccountFormErrors = {}
  const name = form.name.trim()
  if (name === '') errors.name = 'Enter a name.'
  else if (name.length > ACCOUNT_NAME_MAX) errors.name = `Use at most ${ACCOUNT_NAME_MAX} characters.`

  if (form.type === '') errors.type = 'Choose an account type.'

  const balance = parseMoneyInput(form.openingBalance, { allowNegative: true })
  if (!balance.ok) errors.openingBalance = describeMoneyInputError(balance.error)

  const currency = form.currency.trim().toUpperCase()
  if (currency !== '' && !/^[A-Z]{3}$/.test(currency)) errors.currency = 'Use a 3-letter code, like USD or EUR.'

  if (Object.keys(errors).length > 0 || form.type === '' || !balance.ok) {
    return { ok: false, errors }
  }
  return {
    ok: true,
    request: {
      name,
      type: form.type,
      openingBalance: balance.value,
      currency: currency === '' ? undefined : currency,
    },
  }
}
