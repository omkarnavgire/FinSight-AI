import type { ReactNode } from 'react'

export interface EmptyStateProps {
  title: string
  message?: string
  /** Optional call to action, e.g. a "New account" button. */
  action?: ReactNode
}

/** Shown when a list or chart has no data yet. */
export function EmptyState({ title, message, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-gray-700 px-6 py-10 text-center">
      <p className="text-base font-semibold text-gray-100">{title}</p>
      {message === undefined ? null : <p className="max-w-md text-sm text-gray-400">{message}</p>}
      {action ?? null}
    </div>
  )
}
