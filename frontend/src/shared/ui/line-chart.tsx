import { useId } from 'react'
import { type Decimal, toCents, toDecimal } from '@/shared/money/decimal'
import { centsRange, decimalExtremes, ratioInRange } from '@/shared/money/decimal-scale'
import { ChartTable } from './chart-table'

export interface LinePoint {
  key: string
  /** X-axis label, e.g. a formatted date. */
  label: string
  value: Decimal
}

export interface LineChartProps {
  title: string
  points: readonly LinePoint[]
  labelHeading: string
  valueHeading: string
  /** Formats one exact value; used for the axis and the table, so both match the plotted data. */
  formatValue: (value: Decimal) => string
}

const WIDTH = 600
const HEIGHT = 200
const ZERO = toDecimal('0')

/**
 * Line chart in SVG (D-3). Positions come from `bigint` ratios. The axis labels are the real
 * lowest and highest values of `points`, formatted by `formatValue`. A dashed line marks zero when
 * the range crosses it.
 */
export function LineChart({ title, points, labelHeading, valueHeading, formatValue }: LineChartProps) {
  const titleId = useId()
  const range = centsRange(points.map((point) => point.value))
  const limits = decimalExtremes(points.map((point) => point.value))
  const step = points.length > 1 ? WIDTH / (points.length - 1) : 0
  const y = (value: Decimal) => (range === null ? HEIGHT / 2 : HEIGHT - ratioInRange(value, range) * HEIGHT)
  const path = points.map((point, index) => `${(index * step).toFixed(2)},${y(point.value).toFixed(2)}`).join(' ')
  const crossesZero = range !== null && range.min < 0n && range.max > 0n
  const zeroY = crossesZero ? y(ZERO) : null
  const first = points[0]
  const last = points[points.length - 1]
  const flat = limits !== null && toCents(limits.min) === toCents(limits.max)
  const rows = points.map((point) => ({ key: point.key, label: point.label, valueText: formatValue(point.value) }))

  return (
    <figure aria-labelledby={titleId} className="flex flex-col gap-3">
      <figcaption id={titleId} className="text-base font-semibold">
        {title}
      </figcaption>
      <div aria-hidden="true" className="grid grid-cols-[auto_1fr] gap-2 text-xs text-gray-400">
        <div className={`flex flex-col py-1 text-right tabular-nums ${flat ? 'justify-center' : 'justify-between'}`}>
          {limits === null ? null : <span data-axis="max">{formatValue(limits.max)}</span>}
          {limits === null || flat ? null : <span data-axis="min">{formatValue(limits.min)}</span>}
        </div>
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" className="h-48 w-full rounded bg-gray-900">
          {zeroY === null ? null : (
            <line x1={0} x2={WIDTH} y1={zeroY} y2={zeroY} className="stroke-gray-500" strokeDasharray="6 6" vectorEffect="non-scaling-stroke" />
          )}
          {points.length > 1 ? (
            <polyline points={path} fill="none" className="stroke-sky-400" strokeWidth={2} vectorEffect="non-scaling-stroke" />
          ) : null}
          {points.length === 1 && first !== undefined ? <circle cx={WIDTH / 2} cy={y(first.value)} r={4} className="fill-sky-400" /> : null}
        </svg>
        <span />
        <div className="flex justify-between">
          <span>{first?.label ?? ''}</span>
          <span>{last !== first ? (last?.label ?? '') : ''}</span>
        </div>
      </div>
      <ChartTable caption={title} labelHeading={labelHeading} valueHeading={valueHeading} rows={rows} />
    </figure>
  )
}
