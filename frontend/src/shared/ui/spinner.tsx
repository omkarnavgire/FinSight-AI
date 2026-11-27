export interface SpinnerProps {
  /** Extra Tailwind classes, e.g. a size or colour. */
  className?: string
}

/** Decorative spinning ring. Hidden from screen readers; pair it with visible or status text. */
export function Spinner({ className }: SpinnerProps) {
  return (
    <span
      aria-hidden="true"
      className={`inline-block h-5 w-5 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none ${className ?? ''}`}
    />
  )
}
