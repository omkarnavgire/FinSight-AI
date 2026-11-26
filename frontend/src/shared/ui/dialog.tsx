import { type ReactNode, useEffect, useId, useRef } from 'react'

export interface DialogProps {
  open: boolean
  title: string
  /** Called on Esc, the close button, or a programmatic close. */
  onClose: () => void
  children: ReactNode
}

/**
 * Modal dialog on the native `<dialog>` element (focus trap, Esc and backdrop come from the
 * browser). Full screen on mobile, centred card from `md` up. Children mount only while open,
 * so forms inside start fresh each time.
 */
export function Dialog({ open, title, onClose, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (dialog === null) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onClose={onClose}
      className="m-0 h-full max-h-none w-full max-w-none bg-gray-900 p-0 text-gray-100 backdrop:bg-black/60 md:m-auto md:h-auto md:max-h-[90vh] md:max-w-lg md:rounded-lg md:border md:border-gray-700"
    >
      {open ? (
        <div className="flex h-full flex-col">
          <div className="flex items-center justify-between gap-4 border-b border-gray-700 px-4 py-3">
            <h2 id={titleId} className="text-lg font-semibold">
              {title}
            </h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="min-h-11 min-w-11 rounded-md text-xl text-gray-300 hover:bg-gray-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4">{children}</div>
        </div>
      ) : null}
    </dialog>
  )
}
