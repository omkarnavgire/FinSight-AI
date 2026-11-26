import type { AccountType, Category, FraudRuleName, Severity } from '@/shared/types/api'

/** Display labels for backend enums. Keys match the Java enums exactly. */
export const CATEGORY_LABELS: Readonly<Record<Category, string>> = {
  GROCERIES: 'Groceries',
  TRANSPORT: 'Transport',
  UTILITIES: 'Utilities',
  DINING: 'Dining',
  ENTERTAINMENT: 'Entertainment',
  INCOME: 'Income',
  OTHER: 'Other',
}

export const CATEGORIES: readonly Category[] = Object.keys(CATEGORY_LABELS) as Category[]

export const ACCOUNT_TYPE_LABELS: Readonly<Record<AccountType, string>> = {
  CHECKING: 'Checking',
  SAVINGS: 'Savings',
  CREDIT: 'Credit',
}

export const ACCOUNT_TYPES: readonly AccountType[] = Object.keys(ACCOUNT_TYPE_LABELS) as AccountType[]

export const FRAUD_RULE_LABELS: Readonly<Record<FraudRuleName, string>> = {
  DUPLICATE_TRANSACTION: 'Duplicate transaction',
  ABNORMAL_AMOUNT: 'Unusually large amount',
  UNSEEN_COUNTRY: 'New country',
}

export const SEVERITY_LABELS: Readonly<Record<Severity, string>> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
}
