package com.finsight.fraud.dto;

import java.math.BigDecimal;

/**
 * A transaction about to be created, as seen by the fraud rules (T-52). Built by the transactions
 * module before its INSERT, so the fraud module never imports transactions classes (Q-1).
 *
 * @param accountId   target account; already confirmed owned and active by the caller
 * @param amount      signed amount, non-zero
 * @param category    category enum name, e.g. {@code "DINING"}
 * @param description optional free text; {@code null} allowed
 * @param countryCode ISO 3166-1 alpha-2, already validated
 */
public record FraudCandidate(Long accountId,
                             BigDecimal amount,
                             String category,
                             String description,
                             String countryCode) {
}
