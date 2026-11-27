export interface FieldMessagesProps {
  hintId: string
  errorId: string
  hint?: string
  error?: string
}

/** Hint and error lines under a form field. Ids match `useFieldIds`. */
export function FieldMessages({ hintId, errorId, hint, error }: FieldMessagesProps) {
  return (
    <>
      {hint === undefined ? null : (
        <p id={hintId} className="text-xs text-gray-400">
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p id={errorId} className="text-sm text-red-400">
          {error}
        </p>
      )}
    </>
  )
}
