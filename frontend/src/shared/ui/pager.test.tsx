import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Pager } from './pager'

describe('Pager', () => {
  it('is hidden for a single page', () => {
    const { container } = render(<Pager page={0} totalPages={1} onPageChange={() => undefined} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows a 1-based position and moves by one page', () => {
    const onPageChange = vi.fn()
    render(<Pager page={1} totalPages={3} onPageChange={onPageChange} />)
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(onPageChange.mock.calls).toEqual([[0], [2]])
  })

  it('disables Previous on the first page and Next on the last', () => {
    const { rerender } = render(<Pager page={0} totalPages={2} onPageChange={() => undefined} />)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    rerender(<Pager page={1} totalPages={2} onPageChange={() => undefined} />)
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })
})
