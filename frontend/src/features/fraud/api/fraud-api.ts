/**
 * Fraud-alert endpoints (`/api/v1/fraud/alerts`, T-73). The list holds only unacknowledged alerts,
 * newest first. Alerts carry no money. Responses are checked with the wire readers.
 */
import { apiClient } from '@/shared/api/client'
import { asRecord, wireBoolean, wireEnum, wireInteger, wireNullableString, wirePage, wireString } from '@/shared/api/wire'
import { FRAUD_RULE_LABELS, SEVERITY_LABELS } from '@/shared/domain/categories'
import type { FraudAlertDto, FraudRuleName, PageResponse, Severity } from '@/shared/types/api'

const RULES = Object.keys(FRAUD_RULE_LABELS) as FraudRuleName[]
const SEVERITIES = Object.keys(SEVERITY_LABELS) as Severity[]

/** Reads one `FraudAlertDto`. */
export function readFraudAlert(value: unknown): FraudAlertDto {
  const record = asRecord(value, 'FraudAlert')
  return {
    id: wireInteger(record, 'id'),
    transactionId: wireInteger(record, 'transactionId'),
    rule: wireEnum(record, 'rule', RULES),
    severity: wireEnum(record, 'severity', SEVERITIES),
    description: wireString(record, 'description'),
    acknowledged: wireBoolean(record, 'acknowledged'),
    acknowledgedAt: wireNullableString(record, 'acknowledgedAt'),
    createdAt: wireString(record, 'createdAt'),
  }
}

/** `GET /fraud/alerts?page&size`: one page of unacknowledged alerts. */
export async function listFraudAlerts(page: number, size: number): Promise<PageResponse<FraudAlertDto>> {
  const response = await apiClient.get<unknown>('/fraud/alerts', { params: { page, size } })
  return wirePage(response.data, readFraudAlert)
}

/** How many alerts are unacknowledged: one item is asked for, `totalElements` is the count. */
export async function countFraudAlerts(): Promise<number> {
  return (await listFraudAlerts(0, 1)).totalElements
}

/** `PATCH /fraud/alerts/{id}/acknowledge`: dismisses the alert. */
export async function acknowledgeFraudAlert(id: number): Promise<FraudAlertDto> {
  const response = await apiClient.patch<unknown>(`/fraud/alerts/${encodeURIComponent(String(id))}/acknowledge`)
  return readFraudAlert(response.data)
}
