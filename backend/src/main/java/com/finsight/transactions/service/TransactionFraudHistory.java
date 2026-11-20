package com.finsight.transactions.service;

import com.finsight.fraud.service.FraudHistory;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Transactions-module implementation of the fraud module's {@link FraudHistory} (Q-1).
 *
 * <p>Every query is scoped to {@link SecurityUtils#currentUserId()} through {@code accounts.user_id}.
 * Read-only. Deliberately has no {@code @Transactional}: it always runs inside
 * {@code TransactionService.create()}'s transaction, before the new row is inserted, so the
 * transaction being evaluated is never part of the history (C-2, C-3).
 */
@Component
public class TransactionFraudHistory implements FraudHistory {

    private final TransactionRepository transactionRepository;

    public TransactionFraudHistory(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Optional<LocalDateTime> latestDuplicateCreatedAt(Long accountId, BigDecimal amount, String description) {
        return transactionRepository
                .findLatestSameAmountAndDescription(SecurityUtils.currentUserId(), accountId, amount, description)
                .map(Transaction::getCreatedAt);
    }

    @Override
    public CategoryStats categoryStats(String category, LocalDate from, LocalDate to) {
        var row = transactionRepository.categoryStats(SecurityUtils.currentUserId(), category, from, to);
        return new CategoryStats(row.getTxCount().longValue(), row.getTotal());
    }

    @Override
    public boolean hasAnyTransaction() {
        return transactionRepository.anyTransactionFlag(SecurityUtils.currentUserId()).intValue() == 1;
    }

    @Override
    public boolean hasUsedCountry(String countryCode) {
        return transactionRepository.usedCountryFlag(SecurityUtils.currentUserId(), countryCode).intValue() == 1;
    }
}
