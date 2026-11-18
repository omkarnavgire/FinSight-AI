package com.finsight.transactions.service;

import com.finsight.accounts.service.AccountService;
import com.finsight.shared.country.CountryCodes;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.InvalidCountryCodeException;
import com.finsight.shared.exception.TransactionNotEditableException;
import com.finsight.shared.exception.TransactionNotFoundException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.shared.pagination.PageResponse;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.TransactionDto;
import com.finsight.transactions.dto.TransactionFilter;
import com.finsight.transactions.dto.UpdateTransactionRequest;
import com.finsight.transactions.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Transaction business logic (T-28).
 *
 * <h2>Ownership</h2>
 * The caller's identity always comes from {@link SecurityUtils#currentUserId()}; no method accepts
 * a user ID. Every lookup joins {@code accounts.user_id}, so another user's transaction behaves
 * exactly like a missing one: {@link TransactionNotFoundException} (404).
 *
 * <h2>Atomicity (NFR-03-2, NFR-03-3)</h2>
 * Each write method is one {@code @Transactional} block. The row change and the balance change
 * ({@link AccountService#applyDelta}, {@code MANDATORY}) commit together or not at all. Exceptions
 * are never caught here, so any failure rolls back everything.
 *
 * <h2>Persistence-context rule</h2>
 * {@code applyDelta} flushes pending changes and then clears the persistence context. Therefore
 * every entity change in a method is made <em>before</em> {@code applyDelta}, and nothing is
 * changed on a loaded entity afterwards (it would be detached and the change silently lost).
 *
 * <h2>Fraud detection</h2>
 * The fraud rule step and fraud-alert insert from T-28's creation order are added by T-52/T-54
 * (Phase 9) at the marked places in {@link #create}.
 */
@Service
public class TransactionService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final CategoryResolver categoryResolver;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountService accountService,
                              CategoryResolver categoryResolver) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.categoryResolver = categoryResolver;
    }

    /**
     * Adds a manual transaction and applies its amount to the account balance (FR-03-3).
     *
     * <p>Order: validate country code (400) → confirm owned, active account (404 / 409)
     * → [Phase 9: fraud rules] → resolve category → INSERT → balance update
     * → [Phase 9: fraud-alert INSERT]. Nothing is written before the checks pass.
     *
     * @param request validated request
     * @return the created transaction
     */
    @Transactional
    public TransactionDto create(CreateTransactionRequest request) {
        requireValidCountryCode(request.countryCode());
        requireNonZero(request.amount());
        accountService.requireOwnedActive(request.accountId());

        // Phase 9 (T-52/T-54): run FraudDetectionService rules here, before the INSERT.

        var category = request.category() != null
                ? request.category()
                : categoryResolver.resolve(request.description(), request.amount());
        var saved = transactionRepository.save(Transaction.manual(
                request.accountId(), request.amount(), category,
                request.description(), request.transactionDate(), request.countryCode()));
        var result = TransactionDto.from(saved);

        accountService.applyDelta(request.accountId(), request.amount());

        // Phase 9 (T-52/T-54): INSERT fraud alerts for the fired rules here, in this transaction.
        return result;
    }

    /**
     * Edits a manual transaction and applies only the net delta to the balance (FR-03-4).
     *
     * <p>The row is locked first, so concurrent edits are serialised and each delta is computed
     * from the latest committed amount. The delta is applied exactly once, even when it is zero
     * (the zero-delta call also confirms the account is still active).
     *
     * @param transactionId transaction to edit
     * @param request       new values
     * @return the updated transaction
     * @throws TransactionNotFoundException    if missing or not owned (404)
     * @throws TransactionNotEditableException if not a manual transaction (409)
     */
    @Transactional
    public TransactionDto update(Long transactionId, UpdateTransactionRequest request) {
        requireNonZero(request.amount());
        var transaction = loadOwnedForUpdate(transactionId);
        requireManual(transaction);
        accountService.requireOwnedActive(transaction.getAccountId());

        var accountId = transaction.getAccountId();
        var delta = request.amount().subtract(transaction.getAmount());
        transaction.edit(request.amount(), request.transactionDate(), request.category(), request.description());
        var result = TransactionDto.from(transaction);

        // Must stay last: flushes the edit above, then clears the persistence context.
        accountService.applyDelta(accountId, delta);
        return result;
    }

    /**
     * Deletes a manual transaction and reverses its amount on the balance (FR-03-5).
     *
     * @param transactionId transaction to delete
     * @throws TransactionNotFoundException    if missing or not owned (404)
     * @throws TransactionNotEditableException if not a manual transaction (409)
     */
    @Transactional
    public void delete(Long transactionId) {
        var transaction = loadOwnedForUpdate(transactionId);
        requireManual(transaction);
        accountService.requireOwnedActive(transaction.getAccountId());

        var accountId = transaction.getAccountId();
        var reversal = transaction.getAmount().negate();
        transactionRepository.delete(transaction);

        // Must stay last: flushes the DELETE above, then clears the persistence context.
        accountService.applyDelta(accountId, reversal);
    }

    /**
     * Overrides a transaction's category (FR-03-8). The balance is not touched (D-8: allowed for
     * non-manual transactions and on deactivated accounts, since there is no balance effect).
     *
     * @param transactionId transaction to change
     * @param category      new category
     * @return the updated transaction
     * @throws TransactionNotFoundException if missing or not owned (404)
     */
    @Transactional
    public TransactionDto overrideCategory(Long transactionId, Category category) {
        var transaction = transactionRepository.findByIdAndUserId(transactionId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transaction.overrideCategory(category);
        return TransactionDto.from(transaction);
    }

    /**
     * Pages through the caller's transactions, newest first (FR-03-1, FR-03-2).
     *
     * @param filter optional filters; an {@code accountId} that is not the caller's gives 404
     * @param page   zero-based page index, must be {@code >= 0}
     * @param size   page size, must be {@code >= 1}; values above 100 are capped (D-10)
     * @return one page of results
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionDto> search(TransactionFilter filter, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must not be negative");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BadRequestException("from must not be after to");
        }
        if (filter.minAmount() != null && filter.maxAmount() != null
                && filter.minAmount().compareTo(filter.maxAmount()) > 0) {
            throw new BadRequestException("minAmount must not be greater than maxAmount");
        }
        if (filter.accountId() != null) {
            accountService.getMine(filter.accountId()); // 404 if not the caller's account
        }

        var result = transactionRepository.search(
                SecurityUtils.currentUserId(),
                filter.accountId(),
                filter.from(),
                filter.to(),
                filter.category() == null ? null : filter.category().name(),
                filter.minAmount(),
                filter.maxAmount(),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return PageResponse.from(result.map(TransactionDto::from));
    }

    // ---- helpers ----

    private Transaction loadOwnedForUpdate(Long transactionId) {
        return transactionRepository.findByIdAndUserIdForUpdate(transactionId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private static void requireValidCountryCode(String countryCode) {
        if (!CountryCodes.isValid(countryCode)) {
            throw new InvalidCountryCodeException();
        }
    }

    private static void requireNonZero(BigDecimal amount) {
        if (amount.signum() == 0) {
            throw new BadRequestException("amount must not be zero");
        }
    }

    private static void requireManual(Transaction transaction) {
        if (!transaction.isManual()) {
            throw new TransactionNotEditableException(transaction.getId());
        }
    }
}
