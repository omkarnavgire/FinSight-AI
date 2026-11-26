import type { ReactNode } from 'react'
import { Spinner } from './spinner'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'

export interface ButtonProps {
  children: ReactNode
  variant?: ButtonVariant
  type?: 'button' | 'submit'
  onClick?: () => void
  disabled?: boolean
  /** Shows a spinner, sets `aria-busy` and blocks clicks while an action runs. */
  busy?: boolean
  /** Accessible name when the visible content is an icon only. */
  ariaLabel?: string
  /** For buttons that open a menu or dialog. */
  ariaExpanded?: boolean
  className?: string
}

const VARIANT_CLASS: Readonly<Record<ButtonVariant, string>> = {
  primary: 'bg-sky-600 text-white hover:bg-sky-500',
  secondary: 'border border-gray-600 bg-gray-800 text-gray-100 hover:bg-gray-700',
  danger: 'bg-red-700 text-white hover:bg-red-600',
  ghost: 'text-gray-200 hover:bg-gray-800',
}

/** Button with a 44 px touch target, visible focus ring and busy state. */
export function Button({
  children,
  variant = 'primary',
  type = 'button',
  onClick,
  disabled,
  busy = false,
  ariaLabel,
  ariaExpanded,
  className,
}: ButtonProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled === true || busy}
      aria-busy={busy ? true : undefined}
      aria-label={ariaLabel}
      aria-expanded={ariaExpanded}
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400 disabled:cursor-not-allowed disabled:opacity-60 ${VARIANT_CLASS[variant]} ${className ?? ''}`}
    >
      {busy ? <Spinner className="h-4 w-4" /> : null}
      {children}
    </button>
  )
}
