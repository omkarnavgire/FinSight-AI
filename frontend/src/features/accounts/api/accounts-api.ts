/**
 * Accounts endpoints (`/api/v1/accounts`, T-68). All money stays exact: responses are read with
 * `wireDecimal`, request money is written with `decimalToJson`.
 */
import { apiClient } from '@/shared/api/client'
import { decimalToJson } from '@/shared/api/lossless-json'
import { asRecord, wireBoolean, wireDecimal, wireEnum, wireInteger, wirePage, wireString } from '@/shared/api/wire'
import { ACCOUNT_TYPES } from '@/shared/domain/categories'
import type { AccountDto, CreateAccountRequest, PageResponse } from '@/shared/types/api'

/** Backend page-size cap (`PageParams.MAX_SIZE`). */
export const MAX_PAGE_SIZE = 100

/** Most pages `listAllAccounts` will read (2,000 accounts). */
export const MAX_ACCOUNT_PAGES = 20

/** More accounts than `listAllAccounts` reads; a partial list would give wrong currency data. */
export class TooManyAccountsError extends Error {
  constructor() {
    super(`More than ${MAX_PAGE_SIZE * MAX_ACCOUNT_PAGES} accounts`)
    this.name = 'TooManyAccountsError'
  }
}

/** Reads one `AccountDto`. */
export function readAccount(value: unknown): AccountDto {
  const record = asRecord(value, 'Account')
  return {
    id: wireInteger(record, 'id'),
    name: wireString(record, 'name'),
    type: wireEnum(record, 'type', ACCOUNT_TYPES),
    openingBalance: wireDecimal(record, 'openingBalance'),
    balance: wireDecimal(record, 'balance'),
    currency: wireString(record, 'currency'),
    active: wireBoolean(record, 'active'),
    createdAt: wireString(record, 'createdAt'),
  }
}

/** `GET /accounts?page&size`: one page, oldest first. */
export async function listAccounts(page: number, size: number): Promise<PageResponse<AccountDto>> {
  const response = await apiClient.get<unknown>('/accounts', { params: { page, size } })
  return wirePage(response.data, readAccount)
}

/**
 * Every account of the caller, active and inactive, read page by page.
 *
 * @throws TooManyAccountsError instead of returning a partial list
 */
export async function listAllAccounts(): Promise<AccountDto[]> {
  const accounts: AccountDto[] = []
  for (let page = 0; page < MAX_ACCOUNT_PAGES; page++) {
    const result = await listAccounts(page, MAX_PAGE_SIZE)
    accounts.push(...result.content)
    if (result.last || result.content.length === 0) return accounts
  }
  throw new TooManyAccountsError()
}

/** `POST /accounts` (201). A blank currency is left out so the backend default (USD) applies. */
export async function createAccount(request: CreateAccountRequest): Promise<AccountDto> {
  const response = await apiClient.post<unknown>('/accounts', {
    name: request.name,
    type: request.type,
    openingBalance: decimalToJson(request.openingBalance),
    currency: request.currency,
  })
  return readAccount(response.data)
}

/** `DELETE /accounts/{id}` (204): deactivates the account. */
export async function deactivateAccount(id: number): Promise<void> {
  await apiClient.delete(`/accounts/${encodeURIComponent(String(id))}`)
}
