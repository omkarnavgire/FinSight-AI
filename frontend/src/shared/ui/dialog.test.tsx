import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Dialog } from './dialog'
import { ConfirmDialog } from './confirm-dialog'

describe('Dialog', () => {
  it('opens as a labelled modal and mounts children only while open', () => {
    const { rerender } = render(
      <Dialog open={false} title="New account" onClose={() => undefined}>
        <p>Form</p>
      </Dialog>,
    )
    expect(screen.queryByText('Form')).not.toBeInTheDocument()

    rerender(
      <Dialog open title="New account" onClose={() => undefined}>
        <p>Form</p>
      </Dialog>,
    )
    expect(screen.getByRole('dialog', { name: 'New account' })).toBeInTheDocument()
    expect(screen.getByText('Form')).toBeInTheDocument()
  })

  it('calls onClose from the close button', () => {
    const onClose = vi.fn()
    render(
      <Dialog open title="Edit" onClose={onClose}>
        <p>Body</p>
      </Dialog>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalled()
  })
})

describe('ConfirmDialog', () => {
  it('confirms or cancels', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog open title="Delete budget?" confirmLabel="Delete" danger onConfirm={onConfirm} onCancel={onCancel}>
        This cannot be undone.
      </ConfirmDialog>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('blocks a second confirm while busy', () => {
    const onConfirm = vi.fn()
    render(
      <ConfirmDialog open busy title="Delete?" confirmLabel="Delete" onConfirm={onConfirm} onCancel={() => undefined}>
        Sure?
      </ConfirmDialog>,
    )
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  })
})
