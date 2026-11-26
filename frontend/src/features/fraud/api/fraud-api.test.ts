import { beforeEach, describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { acknowledgeFraudAlert, countFraudAlerts, listFraudAlerts, readFraudAlert } from './fraud-api'
import { apiClient } from '@/shared/api/client'
import { clearAccessToken } from '@/shared/api/auth-token'
import { parseLosslessJson } from '@/shared/api/lossless-json'
import { WireFormatError } from '@/shared/api/wire'
import { type MockReply, mockAdapter } from '@/test/mock-adapter'
import { fraudAlert, json, page } from '@/test/fixtures'

let calls: InternalAxiosRequestConfig[]

function backend(reply: MockReply): void {
  apiClient.defaults.adapter = mockAdapter((config) => {
    calls.push(config)
    return reply
  })
}

beforeEach(() => {
  clearAccessToken()
  calls = []
})

describe('readFraudAlert', () => {
  it('maps every field', () => {
    expect(readFraudAlert(parseLosslessJson(json(fraudAlert({ id: 3, rule: 'UNSEEN_COUNTRY', severity: 'HIGH' }))))).toEqual({
      id: 3,
      transactionId: 10,
      rule: 'UNSEEN_COUNTRY',
      severity: 'HIGH',
      description: 'Amount is more than 3 times your usual spending in this category',
      acknowledged: false,
      acknowledgedAt: null,
      createdAt: '2026-03-15T12:00:01',
    })
  })

  it('refuses unknown rules and severities', () => {
    expect(() => readFraudAlert(parseLosslessJson(json(fraudAlert()).replace('"ABNORMAL_AMOUNT"', '"VELOCITY"')))).toThrow(WireFormatError)
    expect(() => readFraudAlert(parseLosslessJson(json(fraudAlert()).replace('"MEDIUM"', '"CRITICAL"')))).toThrow(WireFormatError)
  })
})

describe('fraud API', () => {
  it('lists with page and size', async () => {
    backend({ status: 200, body: json(page([fraudAlert()])) })
    const result = await listFraudAlerts(1, 20)
    expect(calls[0]?.url).toBe('/fraud/alerts')
    expect(calls[0]?.params).toEqual({ page: 1, size: 20 })
    expect(result.content).toHaveLength(1)
  })

  it('counts with a one-item page and totalElements', async () => {
    backend({ status: 200, body: json(page([fraudAlert()], { size: 1, totalElements: 7 })) })
    expect(await countFraudAlerts()).toBe(7)
    expect(calls[0]?.params).toEqual({ page: 0, size: 1 })
  })

  it('acknowledges with PATCH', async () => {
    backend({ status: 200, body: json(fraudAlert({ id: 3, acknowledged: true, acknowledgedAt: '2026-03-16T08:00:00' })) })
    const result = await acknowledgeFraudAlert(3)
    expect(calls[0]?.method).toBe('patch')
    expect(calls[0]?.url).toBe('/fraud/alerts/3/acknowledge')
    expect(result).toMatchObject({ acknowledged: true, acknowledgedAt: '2026-03-16T08:00:00' })
  })

  it('passes a 404 through', async () => {
    backend({ status: 404, body: '{"status":404,"detail":"Fraud alert not found"}' })
    await expect(acknowledgeFraudAlert(9)).rejects.toMatchObject({ response: { status: 404 } })
  })
})
