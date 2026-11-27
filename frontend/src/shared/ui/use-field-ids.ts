import { useId } from 'react'

export interface FieldIds {
  inputId: string
  hintId: string
  errorId: string
  /** Value for `aria-describedby`, or `undefined` when there is no hint or error. */
  describedBy: string | undefined
}

/** Stable ids that link a field to its hint and error text. */
export function useFieldIds(hasHint: boolean, hasError: boolean): FieldIds {
  const base = useId()
  const hintId = `${base}-hint`
  const errorId = `${base}-error`
  const parts = [hasHint ? hintId : null, hasError ? errorId : null].filter((id): id is string => id !== null)
  return { inputId: base, hintId, errorId, describedBy: parts.length === 0 ? undefined : parts.join(' ') }
}
