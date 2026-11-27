import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ProgressBar } from './progress-bar'
import { progressLevel } from './progress-level'
import { toDecimal } from '@/shared/money/decimal'

describe('progressLevel', () => {
  it('uses the 80 % and 100 % alert levels exactly', () => {
    expect(progressLevel(toDecimal('79.99'))).toBe('ok')
    expect(progressLevel(toDecimal('80.00'))).toBe('near')
    expect(progressLevel(toDecimal('99.99'))).toBe('near')
    expect(progressLevel(toDecimal('100.00'))).toBe('over')
  })
})

describe('ProgressBar', () => {
  it('exposes the value and the level in text', () => {
    render(<ProgressBar label="Dining budget" percent={toDecimal('85.5')} />)
    const bar = screen.getByRole('progressbar', { name: 'Dining budget' })
    expect(bar).toHaveAttribute('aria-valuenow', '85.5')
    expect(bar).toHaveAttribute('aria-valuetext', '85.50% used, near the limit')
    expect(screen.getByText('Near the limit')).toBeInTheDocument()
  })

  it('clamps the fill but shows the real value above 100 %', () => {
    render(<ProgressBar label="Transport budget" percent={toDecimal('142.30')} />)
    const bar = screen.getByRole('progressbar', { name: 'Transport budget' })
    expect(bar).toHaveAttribute('aria-valuenow', '100')
    expect(screen.getByText('142.30% used')).toBeInTheDocument()
    expect(screen.getByText('Over the limit')).toBeInTheDocument()
  })
})
