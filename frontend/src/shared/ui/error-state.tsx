import { toApiError } from '@/shared/api/api-error'
import { Alert } from './alert'
import { Button } from './button'

export interface ErrorStateProps {
  /** The thrown value from a failed query; shown through `toApiError`, never raw. */
  error: unknown
  title?: string
  onRetry?: () => void
}

/** Failed-load state with a safe message and an optional retry. */
export function ErrorState({ error, title = 'Could not load this', onRetry }: ErrorStateProps) {
  const { message } = toApiError(error)
  return (
    <Alert tone="error" title={title}>
      <p>{message}</p>
      {onRetry === undefined ? null : (
        <div className="mt-3">
          <Button variant="secondary" onClick={onRetry}>
            Try again
          </Button>
        </div>
      )}
    </Alert>
  )
}
