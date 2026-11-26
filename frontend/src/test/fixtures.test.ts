import { describe, expect, it } from 'vitest'
import { account, breakdown, forecast, json, page, transaction } from './fixtures'
import { parseLosslessJson } from '@/shared/api/lossless-json'

describe('fixtures', () => {
  it('write money as bare number literals, like Jackson', () => {
    expect(json(account({ balance: '-250.00' }))).toContain('"balance":-250.00')
    expect(json(transaction({ amount: '90071992547409.93' }))).toContain('"amount":90071992547409.93')
  })

  it('round-trip through the lossless parser exactly', () => {
    const parsed = parseLosslessJson(json(page([account({ balance: '1250.40' })], { totalElements: 41, size: 20 })))
    expect(parsed).toMatchObject({
      content: [{ balance: '1250.40', currency: 'USD' }],
      totalElements: 41,
      totalPages: 3,
      last: false,
    })
  })

  it('build nested money fields', () => {
    const text = json(breakdown([{ category: 'DINING', amount: '12.30', percent: '100.00' }], '12.30'))
    expect(parseLosslessJson(text)).toMatchObject({ total: '12.30', items: [{ amount: '12.30', percent: '100.00' }] })
    expect(parseLosslessJson(json(forecast({ reason: 'NO_HISTORY', lowConfidence: true })))).toMatchObject({
      reason: 'NO_HISTORY',
      lowConfidence: true,
    })
  })
})
