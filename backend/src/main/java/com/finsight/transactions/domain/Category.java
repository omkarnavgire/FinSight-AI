package com.finsight.transactions.domain;

/**
 * Transaction categories (FR-03-7). Stored in {@code transactions.category} as the enum name,
 * e.g. {@code GROCERIES}.
 */
public enum Category {
    GROCERIES,
    TRANSPORT,
    UTILITIES,
    DINING,
    ENTERTAINMENT,
    INCOME,
    OTHER
}
