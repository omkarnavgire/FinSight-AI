import { describe, expect, it } from 'vitest'
import { formatMoney, formatPercent, percentForWidth } from './format-money'
import { formatDate, formatDateTime, formatMonth, todayIsoDate } from './format-date'
import { type Decimal, toDecimal } from '@/shared/money/decimal'

const d = (text: string): Decimal => toDecimal(text)

describe('formatMoney', () => {
  it('groups digits and keeps exact cents', () => {
    expect(formatMoney(d('1234.56'), 'USD')).toBe('1,234.56 USD')
    expect(formatMoney(d('0.29'))).toBe('0.29')
    expect(formatMoney(d('9999999999999.99'), 'EUR')).toBe('9,999,999,999,999.99 EUR')
  })

  it('uses a real minus sign and no negative zero', () => {
    expect(formatMoney(d('-250.00'), 'USD')).toBe('\u2212250.00 USD')
    expect(formatMoney(d('-0.05'))).toBe('\u22120.05')
    expect(formatMoney(d('-0'))).toBe('0.00')
  })

  it('shows values beyond the float-safe range exactly', () => {
    expect(formatMoney(d('90071992547409.93'))).toBe('90,071,992,547,409.93')
  })
})

describe('percentages', () => {
  it('formats backend percentages', () => {
    expect(formatPercent(d('12.5'))).toBe('12.50%')
    expect(formatPercent(d('999.99'))).toBe('999.99%')
    expect(formatPercent(d('1000.00'))).toBe('> 999%')
  })

  it('clamps widths to 0–100', () => {
    expect(percentForWidth(d('-5'))).toBe(0)
    expect(percentForWidth(d('42.25'))).toBe(42.25)
    expect(percentForWidth(d('180'))).toBe(100)
  })
})

describe('dates', () => {
  it('formats LocalDate without a time-zone shift', () => {
    expect(formatDate('2026-03-01')).toBe('1 Mar 2026')
    expect(formatDate('2026-12-31')).toBe('31 Dec 2026')
    expect(formatDate('not a date')).toBe('not a date')
  })

  it('formats LocalDateTime as sent', () => {
    expect(formatDateTime('2026-03-15T23:45:10')).toBe('15 Mar 2026, 23:45')
    expect(formatDateTime('2026-03-15T08:05')).toBe('15 Mar 2026, 08:05')
    expect(formatDateTime('bad')).toBe('bad')
  })

  it('formats months and today', () => {
    expect(formatMonth(2026, 3)).toBe('March 2026')
    expect(todayIsoDate(new Date(2026, 0, 5))).toBe('2026-01-05')
  })
})
