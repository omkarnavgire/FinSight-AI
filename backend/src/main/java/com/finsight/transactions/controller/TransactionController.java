package com.finsight.transactions.controller;

import com.finsight.shared.pagination.PageResponse;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CategoryOverrideRequest;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.TransactionDto;
import com.finsight.transactions.dto.TransactionFilter;
import com.finsight.transactions.dto.UpdateTransactionRequest;
import com.finsight.transactions.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Transaction endpoints under {@code /api/v1/transactions} (design.md API table).
 *
 * <p>Input validation only; logic, ownership and balance updates are in {@link TransactionService}.
 * No user ID is ever read from the request.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Lists the caller's transactions, newest first, with optional filters.
     *
     * @return 200 with one page; 400 for invalid paging or filter ranges; 404 for another user's account
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<TransactionDto> list(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new TransactionFilter(accountId, from, to, category, minAmount, maxAmount);
        return transactionService.search(filter, page, size);
    }

    /**
     * Adds a manual transaction to one of the caller's active accounts.
     *
     * @return 201 with the transaction (no {@code Location}: the design has no single-item GET, D-5)
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TransactionDto> create(@Valid @RequestBody CreateTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.create(request));
    }

    /**
     * Edits a manual transaction.
     *
     * @return 200 with the updated transaction
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TransactionDto update(@PathVariable Long id, @Valid @RequestBody UpdateTransactionRequest request) {
        return transactionService.update(id, request);
    }

    /**
     * Deletes a manual transaction.
     *
     * @return 204
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Overrides a transaction's category.
     *
     * @return 200 with the updated transaction
     */
    @PatchMapping("/{id}/category")
    @PreAuthorize("isAuthenticated()")
    public TransactionDto overrideCategory(@PathVariable Long id,
                                           @Valid @RequestBody CategoryOverrideRequest request) {
        return transactionService.overrideCategory(id, request.category());
    }
}
