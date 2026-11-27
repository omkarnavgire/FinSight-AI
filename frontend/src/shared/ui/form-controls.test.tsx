import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TextField } from './text-field'
import { SelectField } from './select-field'
import { Button } from './button'
import { Alert } from './alert'
import { Badge } from './badge'

describe('TextField', () => {
  it('links label, hint and error', () => {
    render(<TextField label="Email" value="" onChange={() => undefined} hint="Work email" error="Email is required" />)
    const input = screen.getByLabelText('Email')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Work email Email is required')
  })

  it('passes typed text and input attributes through', () => {
    const onChange = vi.fn()
    render(<TextField label="Password" type="password" autoComplete="current-password" value="" onChange={onChange} />)
    const input = screen.getByLabelText('Password')
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveAttribute('autocomplete', 'current-password')
    fireEvent.change(input, { target: { value: 's3cret!' } })
    expect(onChange).toHaveBeenCalledWith('s3cret!')
  })
})

describe('SelectField', () => {
  const options = [
    { value: 'CHECKING', label: 'Checking' },
    { value: 'SAVINGS', label: 'Savings' },
  ] as const

  it('emits only known option values', () => {
    const onChange = vi.fn()
    render(<SelectField label="Type" value="" placeholder="Choose" options={options} onChange={onChange} />)
    const select = screen.getByLabelText('Type')
    fireEvent.change(select, { target: { value: 'SAVINGS' } })
    fireEvent.change(select, { target: { value: '' } })
    expect(onChange.mock.calls).toEqual([['SAVINGS'], ['']])
  })
})

describe('Button', () => {
  it('blocks clicks and announces busy state', () => {
    const onClick = vi.fn()
    render(
      <Button busy onClick={onClick}>
        Save
      </Button>,
    )
    const button = screen.getByRole('button', { name: 'Save' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })
})

describe('Alert', () => {
  it('uses role alert for errors and states the tone in text', () => {
    render(<Alert tone="error" title="Could not save">Try again.</Alert>)
    expect(screen.getByRole('alert')).toHaveTextContent('Error: Could not save')
  })

  it('renders a static note when not live', () => {
    render(
      <Alert tone="warning" live={false}>
        Mixed currencies.
      </Alert>,
    )
    expect(screen.getByRole('note')).toHaveTextContent('Warning')
  })
})

describe('Badge', () => {
  it('gives screen readers the full label', () => {
    render(<Badge srLabel="3 unacknowledged fraud alerts">3</Badge>)
    expect(screen.getByText('3 unacknowledged fraud alerts')).toHaveClass('sr-only')
  })
})
