import { useId } from 'react'

export interface SegmentOption<T extends string> {
  value: T
  label: string
}

export interface SegmentedControlProps<T extends string> {
  /** Group label, e.g. "Forecast length". */
  legend: string
  options: readonly SegmentOption<T>[]
  value: T
  onChange: (value: T) => void
  disabled?: boolean
}

/**
 * One-of-many choice drawn as buttons, built on native radio inputs (arrow keys, focus and
 * screen-reader support come from the browser).
 */
export function SegmentedControl<T extends string>({ legend, options, value, onChange, disabled }: SegmentedControlProps<T>) {
  const name = useId()
  return (
    <fieldset disabled={disabled} className="flex flex-col gap-1">
      <legend className="text-sm font-medium text-gray-200">{legend}</legend>
      <div className="inline-flex w-fit rounded-md border border-gray-600 bg-gray-900 p-1">
        {options.map((option) => (
          <label key={option.value} className="relative">
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={option.value === value}
              onChange={() => onChange(option.value)}
              className="peer sr-only"
            />
            <span className="flex min-h-11 min-w-16 cursor-pointer items-center justify-center rounded px-3 text-sm text-gray-300 peer-checked:bg-sky-700 peer-checked:text-white peer-focus-visible:ring-2 peer-focus-visible:ring-sky-400">
              {option.label}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
