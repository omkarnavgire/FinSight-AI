/**
 * Transactions endpoints (`/api/v1/transactions`, T-69). Amounts are signed: negative = expense,
 * positive = income. Money is read with `wireDecimal` and sent with `decimalToJson`; filter
 * bounds are sent as exact decimal text.
 */
import { apiClient } from '@/shared/api/client'
import { decimalToJson } from '@/shared/api/lossless-json'
import { asRecord, wireBoolean, wireDecimal, wireEnum, wireInteger, wireNullableString, wirePage, wireString } from '@/shared/api/wire'
import { CATEGORIES } from '@/shared/domain/categories'
import type {
  Category,
  CreateTransactionRequest,
  PageResponse,
  TransactionDto,
  TransactionFilter,
  UpdateTransactionRequest,
} from '@/shared/types/api'

/** Reads one `TransactionDto`. */
export function readTransaction(value: unknown): TransactionDto {
  const record = asRecord(value, 'Transaction')
  return {
    id: wireInteger(record, 'id'),
    accountId: wireInteger(record, 'accountId'),
    amount: wireDecimal(record, 'amount'),
    category: wireEnum(record, 'category', CATEGORIES),
    description: wireNullableString(record, 'description'),
    transactionDate: wireString(record, 'transactionDate'),
    countryCode: wireString(record, 'countryCode'),
    manual: wireBoolean(record, 'manual'),
    createdAt: wireString(record, 'createdAt'),
  }
}

/** Query parameters for a filter; absent fields are left out. */
export function filterParams(filter: TransactionFilter, page: number, size: number): Record<string, string | number> {
  const params: Record<string, string | number> = { page, size }
  if (filter.accountId !== undefined) params.accountId = filter.accountId
  if (filter.from !== undefined) params.from = filter.from
  if (filter.to !== undefined) params.to = filter.to
  if (filter.category !== undefined) params.category = filter.category
  if (filter.minAmount !== undefined) params.minAmount = filter.minAmount
  if (filter.maxAmount !== undefined) params.maxAmount = filter.maxAmount
  return params
}

/** `GET /transactions`: newest date first. */
export async function listTransactions(filter: TransactionFilter, page: number, size: number): Promise<PageResponse<TransactionDto>> {
  const response = await apiClient.get<unknown>('/transactions', { params: filterParams(filter, page, size) })
  return wirePage(response.data, readTransaction)
}

/** `POST /transactions` (201). A missing category means "auto-categorise". */
export async function createTransaction(request: CreateTransactionRequest): Promise<TransactionDto> {
  const response = await apiClient.post<unknown>('/transactions', {
    accountId: request.accountId,
    amount: decimalToJson(request.amount),
    transactionDate: request.transactionDate,
    category: request.category,
    description: request.description,
    countryCode: request.countryCode,
  })
  return readTransaction(response.data)
}

function transactionUrl(id: number): string {
  return `/transactions/${encodeURIComponent(String(id))}`
}

/** `PUT /transactions/{id}`: account and country cannot change. */
export async function updateTransaction(id: number, request: UpdateTransactionRequest): Promise<TransactionDto> {
  const response = await apiClient.put<unknown>(transactionUrl(id), {
    amount: decimalToJson(request.amount),
    transactionDate: request.transactionDate,
    category: request.category,
    description: request.description,
  })
  return readTransaction(response.data)
}

/** `PATCH /transactions/{id}/category`: marks the category as set by the user. */
export async function recategoriseTransaction(id: number, category: Category): Promise<TransactionDto> {
  const response = await apiClient.patch<unknown>(`${transactionUrl(id)}/category`, { category })
  return readTransaction(response.data)
}

/** `DELETE /transactions/{id}` (204): the backend reverses the account balance. */
export async function deleteTransaction(id: number): Promise<void> {
  await apiClient.delete(transactionUrl(id))
}
