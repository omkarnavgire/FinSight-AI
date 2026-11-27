import { type ReactNode, useId } from 'react'
import { Link } from 'react-router'
import type { UseQueryResult } from '@tanstack/react-query'
import { ErrorState } from '@/shared/ui/error-state'
import { Spinner } from '@/shared/ui/spinner'

export interface SummaryCardProps {
  title: string
  /** Module page the card links to. */
  to: string
  linkLabel: string
  children: ReactNode
}

/** One dashboard card: a labelled region with its own content and a link to its module. */
export function SummaryCard({ title, to, linkLabel, children }: SummaryCardProps) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4">
      <h2 id={headingId} className="text-base font-semibold">{title}</h2>
      <div className="flex flex-1 flex-col gap-2">{children}</div>
      <Link to={to} className="mt-auto text-sm text-sky-300 underline focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400">
        {linkLabel}
      </Link>
    </section>
  )
}

export function CardLoading({ label }: { label: string }) {
  return (
    <p role="status" className="flex items-center gap-2 text-sm text-gray-300">
      <Spinner /> Loading {label}…
    </p>
  )
}

export function CardNote({ children }: { children: ReactNode }) {
  return <p className="text-sm text-gray-400">{children}</p>
}

export interface QueryBodyProps<T> {
  query: UseQueryResult<T>
  /** Lower-case name, used in "Loading …" and "Could not load …". */
  label: string
  isEmpty?: (data: T) => boolean
  empty?: ReactNode
  children: (data: T) => ReactNode
}

/** A card's own loading, error (with retry), empty and success states. */
export function QueryBody<T>({ query, label, isEmpty, empty, children }: QueryBodyProps<T>) {
  if (query.isPending) return <CardLoading label={label} />
  if (query.isError) return <ErrorState error={query.error} title={`Could not load ${label}`} onRetry={() => void query.refetch()} />
  if (isEmpty?.(query.data) === true) return <>{empty}</>
  return <>{children(query.data)}</>
}
