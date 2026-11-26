import { useId } from 'react'
import { type Decimal, toCents } from '@/shared/money/decimal'
import { barPercent } from '@/shared/money/decimal-scale'
import { type ChartRow, ChartTable } from './chart-table'

export interface BarDatum extends ChartRow {
  /** Exact value; used for bar length only. Negative values draw no bar. */
  value: Decimal
}

export interface BarChartProps {
  title: string
  data: readonly BarDatum[]
  orientation?: 'horizontal' | 'vertical'
  labelHeading: string
  valueHeading: string
}

function largestOf(data: readonly BarDatum[]): Decimal | null {
  let top: BarDatum | null = null
  for (const datum of data) {
    if (top === null || toCents(datum.value) > toCents(top.value)) top = datum
  }
  return top === null ? null : top.value
}

/**
 * Bar chart drawn with plain elements (D-3, no chart library). Labels show the exact formatted
 * value; lengths are only a visual guide. A table view carries the same data.
 */
export function BarChart({ title, data, orientation = 'horizontal', labelHeading, valueHeading }: BarChartProps) {
  const titleId = useId()
  const largest = largestOf(data)
  const length = (value: Decimal) => (largest === null ? 0 : barPercent(value, largest))

  return (
    <figure aria-labelledby={titleId} className="flex flex-col gap-3">
      <figcaption id={titleId} className="text-base font-semibold">
        {title}
      </figcaption>
      {orientation === 'horizontal' ? (
        <ul aria-hidden="true" className="flex flex-col gap-2">
          {data.map((datum) => (
            <li key={datum.key} className="grid grid-cols-[minmax(6rem,10rem)_1fr] items-center gap-3 text-sm">
              <span className="truncate text-gray-300">{datum.label}</span>
              <span className="flex items-center gap-2">
                <span className="h-4 rounded bg-sky-600" style={{ width: `${length(datum.value)}%` }} />
                <span className="whitespace-nowrap tabular-nums text-gray-200">{datum.valueText}</span>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <ul aria-hidden="true" className="flex h-56 items-stretch gap-2">
          {data.map((datum) => (
            <li key={datum.key} className="flex min-w-0 flex-1 flex-col items-center gap-1 text-xs">
              <span data-bar-value className="max-w-full break-words text-center leading-tight tabular-nums text-gray-200">
                {datum.valueText}
              </span>
              <span className="flex w-full flex-1 items-end">
                <span className="w-full rounded-t bg-sky-600" style={{ height: `${length(datum.value)}%` }} />
              </span>
              <span className="max-w-full truncate text-gray-300">{datum.label}</span>
            </li>
          ))}
        </ul>
      )}
      <ChartTable caption={title} labelHeading={labelHeading} valueHeading={valueHeading} rows={data} />
    </figure>
  )
}
