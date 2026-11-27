import { FieldMessages } from './field-messages'
import { FIELD_CONTROL_CLASS, FIELD_LABEL_CLASS } from './field-styles'
import { useFieldIds } from './use-field-ids'

export interface TextFieldProps {
  label: string
  value: string
  onChange: (value: string) => void
  type?: 'text' | 'email' | 'password' | 'date' | 'search'
  name?: string
  autoComplete?: string
  required?: boolean
  disabled?: boolean
  maxLength?: number
  placeholder?: string
  /** Id of a `<datalist>` with suggestions. */
  list?: string
  hint?: string
  error?: string
}

/** Labelled text input with linked hint and error text. */
export function TextField({ label, value, onChange, type = 'text', hint, error, ...rest }: TextFieldProps) {
  const ids = useFieldIds(hint !== undefined, error !== undefined)
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={ids.inputId} className={FIELD_LABEL_CLASS}>
        {label}
        {rest.required === true ? <span aria-hidden="true"> *</span> : null}
      </label>
      <input
        id={ids.inputId}
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={ids.describedBy}
        className={FIELD_CONTROL_CLASS}
        {...rest}
      />
      <FieldMessages hintId={ids.hintId} errorId={ids.errorId} hint={hint} error={error} />
    </div>
  )
}
