import type { Decimal } from '@/shared/money/decimal'
import { formatPercent, percentForWidth } from '@/shared/format/format-money'
import { type ProgressLevel, progressLevel } from './progress-level'

const LEVEL_CLASS: Readonly<Record<ProgressLevel, string>> = {
  ok: 'bg-emerald-500',
  near: 'bg-amber-400',
  over: 'bg-red-500',
}

const LEVEL_TEXT: Readonly<Record<ProgressLevel, string>> = {
  ok: 'On track',
  near: 'Near the limit',
  over: 'Over the limit',
}

export interface ProgressBarProps {
  /** Accessible name, e.g. "Dining budget". */
  label: string
  /** Exact percentage from the backend; may exceed 100. */
  percent: Decimal
}

/**
 * Progress towards a limit. The fill is clamped to 100 % but the text shows the real value, and
 * the level is written out, so colour is never the only signal.
 */
export function ProgressBar({ label, percent }: ProgressBarProps) {
  const level = progressLevel(percent)
  const width = percentForWidth(percent)
  const text = `${formatPercent(percent)} used, ${LEVEL_TEXT[level].toLowerCase()}`
  return (
    <div className="flex flex-col gap-1">
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={width}
        aria-valuetext={text}
        className="h-3 w-full overflow-hidden rounded-full bg-gray-800"
      >
        <div className={`h-full rounded-full ${LEVEL_CLASS[level]}`} style={{ width: `${width}%` }} />
      </div>
      <p className="flex justify-between text-xs text-gray-300">
        <span>{formatPercent(percent)} used</span>
        <span>{LEVEL_TEXT[level]}</span>
      </p>
    </div>
  )
}
