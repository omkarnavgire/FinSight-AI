import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MoneyInput } from './money-input'

describe('MoneyInput', () => {
  it('links the label to a text field with a decimal keypad', () => {
    render(<MoneyInput label="Amount" value="" onChange={() => undefined} />)
    const input = screen.getByLabelText('Amount')
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveAttribute('inputmode', 'decimal')
  })

  it('passes the raw text through without converting it', () => {
    const onChange = vi.fn()
    render(<MoneyInput label="Amount" value="" onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '0.10' } })
    expect(onChange).toHaveBeenCalledWith('0.10')
  })

  it('shows the value exactly as given', () => {
    render(<MoneyInput label="Amount" value="9999999999999.99" onChange={() => undefined} />)
    expect(screen.getByLabelText('Amount')).toHaveValue('9999999999999.99')
  })

  it('marks an error and links its message', () => {
    render(<MoneyInput label="Amount" value="1.005" error="Use at most 2 digits after the point." onChange={() => undefined} />)
    const input = screen.getByLabelText('Amount')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Use at most 2 digits after the point.')
  })

  it('links a hint when there is no error', () => {
    render(<MoneyInput label="Amount" value="" hint="In USD" onChange={() => undefined} />)
    const input = screen.getByLabelText('Amount')
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(input).toHaveAccessibleDescription('In USD')
  })
})
