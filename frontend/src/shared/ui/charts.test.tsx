import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { BarChart } from './bar-chart'
import { ChartTable } from './chart-table'
import { LineChart } from './line-chart'
import { MixedCurrencyNotice } from './mixed-currency-notice'
import { type Decimal, toDecimal } from '@/shared/money/decimal'
import { formatMoney } from '@/shared/format/format-money'

const bar = (key: string, value: string) => ({
  key,
  label: key,
  value: toDecimal(value),
  valueText: formatMoney(toDecimal(value), 'USD'),
})

describe('ChartTable (C-1)', () => {
  const rows = [{ key: 'a', label: 'Dining', valueText: '12.30 USD' }]

  it('keeps an accessible table while "Show as table" is closed', () => {
    const { container } = render(<ChartTable caption="Spending" labelHeading="Category" valueHeading="Amount" rows={rows} />)
    expect(container.querySelector('details')?.open).toBe(false)
    const table = screen.getByRole('table', { name: 'Spending' })
    expect(table).toHaveClass('sr-only')
    expect(table.closest('details')).toBeNull()
    expect(within(table).getByRole('rowheader', { name: 'Dining' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '12.30 USD' })).toBeInTheDocument()
  })

  it('hides the visible copy from screen readers, so data is announced once', () => {
    const { container } = render(<ChartTable caption="Spending" labelHeading="Category" valueHeading="Amount" rows={rows} />)
    const details = container.querySelector('details') as HTMLDetailsElement
    details.open = true
    expect(screen.getAllByRole('table')).toHaveLength(1)
    expect(details.querySelector('table')).toHaveAttribute('aria-hidden', 'true')
    expect(within(details).getByText('Show as table')).toBeInTheDocument()
  })
})

describe('BarChart', () => {
  it('labels bars with exact values and offers the same data as a table', () => {
    render(
      <BarChart
        title="Spending by category"
        labelHeading="Category"
        valueHeading="Amount"
        data={[bar('Dining', '90071992547409.93'), bar('Transport', '0.29')]}
      />,
    )
    expect(screen.getByRole('figure', { name: 'Spending by category' })).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Spending by category' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual(['Dining90,071,992,547,409.93 USD', 'Transport0.29 USD'])
  })

  it('sizes horizontal bars against the largest value', () => {
    const { container } = render(
      <BarChart title="Top" labelHeading="Category" valueHeading="Amount" data={[bar('A', '200'), bar('B', '50'), bar('C', '-5')]} />,
    )
    const widths = Array.from(container.querySelectorAll<HTMLElement>('ul span.bg-sky-600'), (el) => el.style.width)
    expect(widths).toEqual(['100%', '25%', '0%'])
  })

  it('draws vertical bars by height', () => {
    const { container } = render(
      <BarChart title="Trend" orientation="vertical" labelHeading="Month" valueHeading="Spent" data={[bar('Jan', '10'), bar('Feb', '40')]} />,
    )
    const heights = Array.from(container.querySelectorAll<HTMLElement>('ul span.bg-sky-600'), (el) => el.style.height)
    expect(heights).toEqual(['25%', '100%'])
  })

  it('shows the exact value above every vertical bar (C-3), including zero and negative', () => {
    const { container } = render(
      <BarChart
        title="Trend"
        orientation="vertical"
        labelHeading="Month"
        valueHeading="Spent"
        data={[bar('Jan', '90071992547409.93'), bar('Feb', '0'), bar('Mar', '-5.25')]}
      />,
    )
    const labels = Array.from(container.querySelectorAll('[data-bar-value]'), (el) => el.textContent)
    expect(labels).toEqual(['90,071,992,547,409.93 USD', '0.00 USD', '\u22125.25 USD'])
    const heights = Array.from(container.querySelectorAll<HTMLElement>('ul span.bg-sky-600'), (el) => el.style.height)
    expect(heights).toEqual(['100%', '0%', '0%'])
  })

  it('keeps horizontal bars unchanged: value beside the bar, no vertical labels', () => {
    const { container } = render(<BarChart title="Top" labelHeading="Category" valueHeading="Amount" data={[bar('A', '1')]} />)
    expect(container.querySelector('[data-bar-value]')).toBeNull()
    expect(within(container.querySelector('ul') as HTMLElement).getByText('1.00 USD')).toBeInTheDocument()
  })
})

describe('LineChart (C-2)', () => {
  const point = (date: string, value: string) => ({ key: date, label: date, value: toDecimal(value) })
  const usd = (value: Decimal) => formatMoney(value, 'USD')
  const axis = (container: HTMLElement) => ({
    max: container.querySelector('[data-axis="max"]')?.textContent ?? null,
    min: container.querySelector('[data-axis="min"]')?.textContent ?? null,
  })
  const draw = (points: ReturnType<typeof point>[]) =>
    render(<LineChart title="Projected balance" labelHeading="Date" valueHeading="Balance" formatValue={usd} points={points} />).container

  it('plots every point and lists formatted values in the table', () => {
    const container = draw([point('16 Mar', '100.00'), point('17 Mar', '25.00'), point('18 Mar', '-50.00')])
    expect(container.querySelector('polyline')?.getAttribute('points')).toBe('0.00,0.00 300.00,100.00 600.00,200.00')
    const table = screen.getByRole('table', { name: 'Projected balance' })
    expect(within(table).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['100.00 USD', '25.00 USD', '\u221250.00 USD'])
  })

  it('takes axis labels from the data: positive values', () => {
    const container = draw([point('a', '10.00'), point('b', '90071992547409.93'), point('c', '0.29')])
    expect(axis(container)).toEqual({ max: '90,071,992,547,409.93 USD', min: '0.29 USD' })
    expect(container.querySelector('line')).toBeNull()
  })

  it('takes axis labels from the data: negative values', () => {
    const container = draw([point('a', '-0.01'), point('b', '-250.00')])
    expect(axis(container)).toEqual({ max: '\u22120.01 USD', min: '\u2212250.00 USD' })
    expect(container.querySelector('line')).toBeNull()
  })

  it('takes axis labels from the data: mixed signs, with a zero line', () => {
    const container = draw([point('a', '-120.00'), point('b', '45.50'), point('c', '3.00')])
    expect(axis(container)).toEqual({ max: '45.50 USD', min: '\u2212120.00 USD' })
    expect(container.querySelector('line')).not.toBeNull()
  })

  it('shows one axis label for a single point or a flat line', () => {
    const single = draw([point('a', '5.00')])
    expect(axis(single)).toEqual({ max: '5.00 USD', min: null })
    expect(single.querySelector('polyline')).toBeNull()
    expect(single.querySelector('circle')).not.toBeNull()
  })

  it('shows no axis labels without points', () => {
    expect(axis(draw([]))).toEqual({ max: null, min: null })
  })
})

describe('MixedCurrencyNotice', () => {
  it('names the currencies and says nothing is converted', () => {
    render(<MixedCurrencyNotice currencies={['EUR', 'GBP', 'USD']} subject="this month's spending total" />)
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('Your accounts use EUR, GBP and USD.')
    expect(note).toHaveTextContent("does not convert between currencies, so this month's spending total is not shown as one amount")
  })
})
