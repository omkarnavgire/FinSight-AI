import { FieldMessages } from './field-messages'
import { FIELD_CONTROL_CLASS, FIELD_LABEL_CLASS } from './field-styles'
import { useFieldIds } from './use-field-ids'

export interface SelectOption<T extends string> {
  value: T
  label: string
}

export interface SelectFieldProps<T extends string> {
  label: string
  /** Selected value, or "" for none. */
  value: T | ''
  options: readonly SelectOption<T>[]
  /** Receives the chosen option's value, or "" when the placeholder is chosen. */
  onChange: (value: T | '') => void
  /** Text of an empty first option (e.g. "All accounts"). Omit to force a choice. */
  placeholder?: string
  name?: string
  required?: boolean
  disabled?: boolean
  hint?: string
  error?: string
}

/** Labelled native select. Only values from `options` can be emitted. */
export function SelectField<T extends string>({
  label,
  value,
  options,
  onChange,
  placeholder,
  hint,
  error,
  ...rest
}: SelectFieldProps<T>) {
  const ids = useFieldIds(hint !== undefined, error !== undefined)
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={ids.inputId} className={FIELD_LABEL_CLASS}>
        {label}
        {rest.required === true ? <span aria-hidden="true"> *</span> : null}
      </label>
      <select
        id={ids.inputId}
        value={value}
        onChange={(event) => onChange(options.find((option) => option.value === event.target.value)?.value ?? '')}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={ids.describedBy}
        className={FIELD_CONTROL_CLASS}
        {...rest}
      >
        {placeholder === undefined ? null : <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <FieldMessages hintId={ids.hintId} errorId={ids.errorId} hint={hint} error={error} />
    </div>
  )
}
