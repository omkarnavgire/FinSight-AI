import type { ReactNode } from 'react'

export type AlertTone = 'info' | 'success' | 'warning' | 'error'

export interface AlertProps {
  tone?: AlertTone
  title?: string
  children: ReactNode
  /**
   * `true` (default) announces the message: `role="alert"` for errors, `role="status"` otherwise.
   * `false` renders a static note (`role="note"`) for warnings that stay on screen.
   */
  live?: boolean
}

const TONE_CLASS: Readonly<Record<AlertTone, string>> = {
  info: 'border-sky-700 bg-sky-950 text-sky-100',
  success: 'border-emerald-700 bg-emerald-950 text-emerald-100',
  warning: 'border-amber-600 bg-amber-950 text-amber-100',
  error: 'border-red-700 bg-red-950 text-red-100',
}

const TONE_PREFIX: Readonly<Record<AlertTone, string>> = {
  info: 'Note',
  success: 'Done',
  warning: 'Warning',
  error: 'Error',
}

/** Message box. The tone is also given as text, so colour is never the only signal. */
export function Alert({ tone = 'info', title, children, live = true }: AlertProps) {
  const role = live ? (tone === 'error' ? 'alert' : 'status') : 'note'
  return (
    <div role={role} className={`rounded-md border px-4 py-3 text-sm ${TONE_CLASS[tone]}`}>
      <p className="font-semibold">
        {title === undefined ? (
          TONE_PREFIX[tone]
        ) : (
          <>
            <span className="sr-only">{TONE_PREFIX[tone]}: </span>
            {title}
          </>
        )}
      </p>
      <div className="mt-1">{children}</div>
    </div>
  )
}
