import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { SegmentedControl } from './segmented-control'
import { MonthPicker } from './month-picker'

describe('SegmentedControl', () => {
  const options = [
    { value: '30', label: '30 days' },
    { value: '60', label: '60 days' },
    { value: '90', label: '90 days' },
  ] as const

  it('is a labelled radio group with the current value checked', () => {
    render(<SegmentedControl legend="Forecast length" options={options} value="60" onChange={() => undefined} />)
    expect(screen.getByRole('group', { name: 'Forecast length' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '60 days' })).toBeChecked()
    expect(screen.getByRole('radio', { name: '30 days' })).not.toBeChecked()
  })

  it('emits the chosen value', () => {
    const onChange = vi.fn()
    render(<SegmentedControl legend="Forecast length" options={options} value="30" onChange={onChange} />)
    fireEvent.click(screen.getByRole('radio', { name: '90 days' }))
    expect(onChange).toHaveBeenCalledWith('90')
  })
})

describe('MonthPicker', () => {
  it('shows the current month and year', () => {
    render(<MonthPicker value={{ year: 2026, month: 3 }} onChange={() => undefined} />)
    expect(screen.getByLabelText('Month')).toHaveValue('3')
    expect(screen.getByLabelText('Year')).toHaveValue('2026')
  })

  it('always emits year and month together', () => {
    const onChange = vi.fn()
    render(<MonthPicker value={{ year: 2026, month: 3 }} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Month'), { target: { value: '11' } })
    fireEvent.change(screen.getByLabelText('Year'), { target: { value: '2025' } })
    expect(onChange.mock.calls).toEqual([[{ year: 2026, month: 11 }], [{ year: 2025, month: 3 }]])
  })

  it('offers only the backend year range', () => {
    render(<MonthPicker value={{ year: 2026, month: 3 }} onChange={() => undefined} />)
    const years = Array.from((screen.getByLabelText('Year') as HTMLSelectElement).options, (o) => o.value)
    expect(years[0]).toBe('2100')
    expect(years[years.length - 1]).toBe('2000')
    expect(years).toHaveLength(101)
  })
})
