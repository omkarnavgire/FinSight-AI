import type { ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export interface BadgeProps {
  children: ReactNode
  tone?: BadgeTone
  /** Full text for screen readers (e.g. "3 unacknowledged fraud alerts"); hides the short visible text. */
  srLabel?: string
}

const TONE_CLASS: Readonly<Record<BadgeTone, string>> = {
  neutral: 'bg-gray-700 text-gray-100',
  info: 'bg-sky-800 text-sky-50',
  success: 'bg-emerald-800 text-emerald-50',
  warning: 'bg-amber-700 text-amber-50',
  danger: 'bg-red-700 text-red-50',
}

/** Small pill for counts and states. */
export function Badge({ children, tone = 'neutral', srLabel }: BadgeProps) {
  return (
    <span className={`inline-flex min-w-6 items-center justify-center rounded-full px-2 py-0.5 text-xs font-semibold ${TONE_CLASS[tone]}`}>
      {srLabel === undefined ? (
        children
      ) : (
        <>
          <span aria-hidden="true">{children}</span>
          <span className="sr-only">{srLabel}</span>
        </>
      )}
    </span>
  )
}
