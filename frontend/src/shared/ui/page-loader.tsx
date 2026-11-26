import { Spinner } from './spinner'

export interface PageLoaderProps {
  /** What is loading, read out by screen readers. Default "Loading". */
  label?: string
}

/** Full-area loading state with a polite live region. */
export function PageLoader({ label = 'Loading' }: PageLoaderProps) {
  return (
    <div role="status" aria-live="polite" className="flex min-h-[50vh] items-center justify-center gap-3 text-gray-300">
      <Spinner />
      <span>{label}…</span>
    </div>
  )
}
