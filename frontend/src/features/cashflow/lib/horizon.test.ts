import { describe, expect, it } from 'vitest'
import { readHorizonParam, refreshLimitMessage } from './horizon'

describe('readHorizonParam', () => {
  it('reads 30, 60 and 90, and falls back to 30', () => {
    expect(readHorizonParam(new URLSearchParams('horizon=60'))).toBe(60)
    expect(readHorizonParam(new URLSearchParams('horizon=90'))).toBe(90)
    for (const bad of ['', 'horizon=45', 'horizon=abc', 'horizon=30']) {
      expect(readHorizonParam(new URLSearchParams(bad)), bad).toBe(30)
    }
  })
})

describe('refreshLimitMessage', () => {
  it('words the wait in minutes', () => {
    expect(refreshLimitMessage(840)).toBe('You can recalculate once per hour. Try again in 14 minutes.')
    expect(refreshLimitMessage(30)).toBe('You can recalculate once per hour. Try again in 1 minute.')
    expect(refreshLimitMessage(null)).toBe('You can recalculate once per hour. Please try again later.')
  })
})
