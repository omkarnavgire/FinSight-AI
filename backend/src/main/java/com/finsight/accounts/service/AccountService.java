package com.finsight.accounts.service;

import com.finsight.accounts.domain.Account;
import com.finsight.accounts.dto.AccountDto;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.repository.AccountRepository;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.AccountNotFoundException;
import com.finsight.shared.ownership.SecurityUtils;
import com.finsight.shared.pagination.PageParams;
import com.finsight.shared.pagination.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Account business logic and the accounts module's public API for other modules.
 *
 * <p>Every method reads the caller's identity from {@link SecurityUtils#currentUserId()}.
 * No method accepts a user ID, so a client can never act on another user's account (AC-OWN-3).
 * A missing account and another user's account both raise {@link AccountNotFoundException} (404).
 */
@Service
public class AccountService {

    /** Default currency when the request omits one (design default, G-1). */
    static final String DEFAULT_CURRENCY = "USD";

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Opens an account for the caller with {@code balance = openingBalance} (AC-BAL-1).
     *
     * @param request validated request; currency defaults to {@code USD}
     * @return the created account
     */
    @Transactional
    public AccountDto create(CreateAccountRequest request) {
        var userId = SecurityUtils.currentUserId();
        var currency = request.currency() == null ? DEFAULT_CURRENCY : request.currency();
        var account = Account.open(userId, request.name(), request.type(), request.openingBalance(), currency);
        return AccountDto.from(accountRepository.save(account));
    }

    /**
     * Lists all of the caller's accounts, including deactivated ones (FR-02-2, G-4).
     *
     * @return the caller's accounts, oldest first
     */
    @Transactional(readOnly = true)
    public List<AccountDto> listMine() {
        return accountRepository.findByUserIdOrderByCreatedAtAscIdAsc(SecurityUtils.currentUserId())
                .stream()
                .map(AccountDto::from)
                .toList();
    }

    /**
     * One page of the caller's accounts, including deactivated ones, oldest first (Q-5).
     *
     * @param page zero-based page index, must be {@code >= 0}
     * @param size page size, must be {@code >= 1}; values above 100 are capped
     * @return one page of accounts
     * @throws com.finsight.shared.exception.BadRequestException for invalid paging (400)
     */
    @Transactional(readOnly = true)
    public PageResponse<AccountDto> listMinePage(int page, int size) {
        return PageResponse.from(accountRepository
                .findPageByUserIdOrderByCreatedAtAscIdAsc(SecurityUtils.currentUserId(), PageParams.of(page, size))
                .map(AccountDto::from));
    }

    /**
     * Returns one of the caller's accounts.
     *
     * @param accountId account ID from the request path
     * @return the account
     * @throws AccountNotFoundException if missing or owned by someone else
     */
    @Transactional(readOnly = true)
    public AccountDto getMine(Long accountId) {
        return AccountDto.from(loadOwned(accountId));
    }

    /**
     * Deactivates one of the caller's accounts (FR-02-3). Idempotent (G-2).
     * Both balances are left unchanged and all data is retained.
     *
     * @param accountId account ID from the request path
     * @throws AccountNotFoundException if missing or owned by someone else
     */
    @Transactional
    public void deactivate(Long accountId) {
        var account = loadOwned(accountId);
        account.deactivate();
        accountRepository.save(account);
    }

    /**
     * Confirms the caller owns an active account. For use by other modules before a write.
     *
     * <p>This is an early, explicit check. It does not replace the guard inside
     * {@link #applyDelta}, which stays safe even if this method is skipped.
     *
     * @param accountId account to check
     * @return the account view
     * @throws AccountNotFoundException     if missing or owned by someone else (404)
     * @throws AccountDeactivatedException  if owned but deactivated (409)
     */
    @Transactional
    public AccountDto requireOwnedActive(Long accountId) {
        var account = loadOwned(accountId);
        if (!account.isActive()) {
            throw new AccountDeactivatedException(accountId);
        }
        return AccountDto.from(account);
    }

    /**
     * Adds {@code delta} to the balance of the caller's active account (T-21, T-28b).
     *
     * <h2>Transaction and rollback rules</h2>
     * <ol>
     *   <li>{@code Propagation.MANDATORY}: must run inside the caller's transaction, so the balance
     *       change and the record that caused it commit together or not at all (NFR-03-3).
     *       Calling it without a transaction throws {@code IllegalTransactionStateException}.</li>
     *   <li>Owner and {@code active = true} are checked inside one atomic {@code UPDATE}; another
     *       user's balance can never change, and a concurrent deactivation cannot be bypassed.</li>
     *   <li>On failure it throws a {@code RuntimeException}, which marks the caller's whole transaction
     *       for rollback. This is intended. Callers must not catch and continue: Spring would still
     *       roll back at commit and raise {@code UnexpectedRollbackException}.</li>
     *   <li>{@code opening_balance} is never changed.</li>
     *   <li>The persistence context is cleared afterwards (see {@code AccountRepository.applyDelta}).</li>
     * </ol>
     *
     * <p>Intended caller: {@code TransactionService} (Phase 5). Enforced by {@code ModuleBoundaryTest}.
     *
     * @param accountId account whose balance changes
     * @param delta     signed amount to add; zero is allowed
     * @throws AccountNotFoundException    if missing or owned by someone else (404)
     * @throws AccountDeactivatedException if owned but deactivated (409)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyDelta(Long accountId, BigDecimal delta) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(delta, "delta must not be null");
        var userId = SecurityUtils.currentUserId();

        int matched = accountRepository.applyDelta(accountId, userId, delta);
        if (matched == 1) {
            return;
        }
        // No row matched. Read only to choose the right error; the write already did not happen.
        var owned = accountRepository.findByIdAndUserId(accountId, userId);
        if (owned.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        throw new AccountDeactivatedException(accountId);
    }

    private Account loadOwned(Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
