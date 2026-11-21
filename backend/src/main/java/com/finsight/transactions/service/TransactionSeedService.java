package com.finsight.transactions.service;

import com.finsight.accounts.service.AccountService;
import com.finsight.shared.country.CountryCodes;
import com.finsight.shared.exception.InvalidCountryCodeException;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.TransactionDto;
import com.finsight.transactions.repository.TransactionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Bulk-inserts synthetic transactions for {@code DataSeeder} (T-61, Q-1). Exists only in {@code dev}.
 *
 * <p>Unlike {@code TransactionService.create}, it runs <em>no</em> fraud rules and publishes no
 * change events, so seeding produces no unplanned alerts. Ownership and balance rules still hold:
 * every account must be the caller's and active, and each row gets exactly one balance update in
 * the same transaction (NFR-03-3).
 */
@Service
@Profile("dev")
public class TransactionSeedService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    public TransactionSeedService(TransactionRepository transactionRepository, AccountService accountService) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
    }

    /**
     * Inserts the transactions in order, in the caller's transaction.
     *
     * @param requests rows with an explicit category; accounts must belong to the caller
     * @return the inserted transactions, in the same order
     */
    @Transactional
    public List<TransactionDto> seed(List<CreateTransactionRequest> requests) {
        var result = new ArrayList<TransactionDto>(requests.size());
        for (var r : requests) {
            if (!CountryCodes.isValid(r.countryCode())) {
                throw new InvalidCountryCodeException();
            }
            accountService.requireOwnedActive(r.accountId());
            var saved = transactionRepository.save(Transaction.manual(
                    r.accountId(), r.amount(), r.category(), r.description(), r.transactionDate(), r.countryCode()));
            result.add(TransactionDto.from(saved));
            accountService.applyDelta(r.accountId(), r.amount());
        }
        return List.copyOf(result);
    }
}
