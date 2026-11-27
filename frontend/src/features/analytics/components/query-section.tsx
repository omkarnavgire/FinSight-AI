import type { ReactNode } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import { EmptyState } from '@/shared/ui/empty-state'
import { ErrorState } from '@/shared/ui/error-state'
import { Spinner } from '@/shared/ui/spinner'

export interface QuerySectionProps<T> {
  title: string
  query: UseQueryResult<T>
  /** True when the loaded data has nothing to show. */
  isEmpty: (data: T) => boolean
  emptyTitle: string
  emptyMessage?: string
  children: (data: T) => ReactNode
}

/** One analytics block with its own loading, error (with retry), empty and success states. */
export function QuerySection<T>({ title, query, isEmpty, emptyTitle, emptyMessage, children }: QuerySectionProps<T>) {
  const headingId = `analytics-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  let body: ReactNode
  if (query.isPending) {
    body = (
      <p role="status" className="flex items-center gap-2 text-sm text-gray-300">
        <Spinner /> Loading {title.toLowerCase()}…
      </p>
    )
  } else if (query.isError) {
    body = <ErrorState error={query.error} title={`Could not load ${title.toLowerCase()}`} onRetry={() => void query.refetch()} />
  } else if (isEmpty(query.data)) {
    body = <EmptyState title={emptyTitle} message={emptyMessage} />
  } else {
    body = children(query.data)
  }
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4">
      <h2 id={headingId} className="text-lg font-semibold">{title}</h2>
      {body}
    </section>
  )
}
