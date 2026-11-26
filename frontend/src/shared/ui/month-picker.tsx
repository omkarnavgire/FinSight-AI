import { SelectField } from './select-field'

/** Backend limit for periods (AnalyticsParams, PeriodParser). */
export const MIN_PERIOD_YEAR = 2000
export const MAX_PERIOD_YEAR = 2100

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
] as const

const MONTH_OPTIONS = MONTH_NAMES.map((name, index) => ({ value: String(index + 1), label: name }))

export interface MonthValue {
  year: number
  month: number
}

export interface MonthPickerProps {
  value: MonthValue
  onChange: (value: MonthValue) => void
  /** Years offered; defaults to the backend range 2000–2100. */
  minYear?: number
  maxYear?: number
  disabled?: boolean
}

/** Month and year selects. Always emits both together, as the backend requires. */
export function MonthPicker({
  value,
  onChange,
  minYear = MIN_PERIOD_YEAR,
  maxYear = MAX_PERIOD_YEAR,
  disabled,
}: MonthPickerProps) {
  const yearOptions = Array.from({ length: maxYear - minYear + 1 }, (_, index) => {
    const year = String(maxYear - index)
    return { value: year, label: year }
  })
  return (
    <div className="flex flex-wrap gap-3">
      <SelectField
        label="Month"
        value={String(value.month)}
        options={MONTH_OPTIONS}
        disabled={disabled}
        onChange={(month) => {
          if (month !== '') onChange({ year: value.year, month: Number(month) })
        }}
      />
      <SelectField
        label="Year"
        value={String(value.year)}
        options={yearOptions}
        disabled={disabled}
        onChange={(year) => {
          if (year !== '') onChange({ year: Number(year), month: value.month })
        }}
      />
    </div>
  )
}
