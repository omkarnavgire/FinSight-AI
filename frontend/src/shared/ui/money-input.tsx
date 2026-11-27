import { useId } from 'react'

export interface MoneyInputProps {
  /** Visible label text. */
  label: string
  /** Raw text exactly as typed. */
  value: string
  /** Receives raw text; validate it with `parseMoneyInput` before use. */
  onChange: (value: string) => void
  /** Error message to show under the field. */
  error?: string
  /** Help text under the field, e.g. the currency. */
  hint?: string
  name?: string
  required?: boolean
  disabled?: boolean
}

/**
 * Text field for a money amount.
 *
 * Holds the user's text as a string and never converts it to a `number`, so no float
 * rounding can happen while typing. `inputMode="decimal"` shows a numeric keypad on mobile.
 * The parent form validates with `parseMoneyInput` and passes any message as `error`.
 */
export function MoneyInput({ label, value, onChange, error, hint, name, required, disabled }: MoneyInputProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint === undefined ? null : hintId, error === undefined ? null : errorId]
    .filter((part): part is string => part !== null)
    .join(' ')

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-gray-200">
        {label}
        {required === true ? <span aria-hidden="true"> *</span> : null}
      </label>
      <input
        id={id}
        name={name}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        spellCheck={false}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required={required}
        disabled={disabled}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={describedBy === '' ? undefined : describedBy}
        className="min-h-11 rounded-md border border-gray-600 bg-gray-900 px-3 text-gray-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400 disabled:opacity-60 aria-[invalid=true]:border-red-500"
      />
      {hint === undefined ? null : (
        <p id={hintId} className="text-xs text-gray-400">
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p id={errorId} className="text-sm text-red-400">
          {error}
        </p>
      )}
    </div>
  )
}
