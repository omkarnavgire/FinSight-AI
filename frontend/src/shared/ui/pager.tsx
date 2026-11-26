import { Button } from './button'

export interface PagerProps {
  /** Zero-based page, as in `PageResponse.page`. */
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  disabled?: boolean
}

/** Previous / next pager for `PageResponse` lists. Hidden when there is one page or none. */
export function Pager({ page, totalPages, onPageChange, disabled = false }: PagerProps) {
  if (totalPages <= 1) return null
  const isFirst = page <= 0
  const isLast = page >= totalPages - 1
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-3">
      <Button variant="secondary" onClick={() => onPageChange(page - 1)} disabled={disabled || isFirst}>
        Previous
      </Button>
      <span aria-live="polite" className="text-sm text-gray-300">
        Page {Math.min(page + 1, totalPages)} of {totalPages}
      </span>
      <Button variant="secondary" onClick={() => onPageChange(page + 1)} disabled={disabled || isLast}>
        Next
      </Button>
    </nav>
  )
}
