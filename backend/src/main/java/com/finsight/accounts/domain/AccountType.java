package com.finsight.accounts.domain;

/**
 * Supported synthetic account types (FR-02-1). Matches the {@code accounts.type} ENUM in V3.
 */
public enum AccountType {
    CHECKING,
    SAVINGS,
    CREDIT
}
