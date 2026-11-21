package com.finsight.accounts.controller;

import com.finsight.accounts.dto.AccountDto;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.shared.pagination.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Account endpoints under {@code /api/v1/accounts} (design.md API table).
 *
 * <p>Input validation only; all logic and ownership checks are in {@link AccountService}.
 * The controller never passes a user ID: the service reads it from the security context.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Lists the caller's accounts, including deactivated ones, oldest first (Q-5).
     *
     * @return 200 with one page; 400 for an invalid page or size
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<AccountDto> list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return accountService.listMinePage(page, size);
    }

    /**
     * Creates an account for the caller.
     *
     * @param request validated request body
     * @return 201 with the account and a {@code Location} header
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AccountDto> create(@Valid @RequestBody CreateAccountRequest request) {
        var created = accountService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id())).body(created);
    }

    /**
     * Returns one of the caller's accounts.
     *
     * @param id account ID
     * @return 200 with the account; 404 if missing or not owned
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AccountDto get(@PathVariable Long id) {
        return accountService.getMine(id);
    }

    /**
     * Deactivates one of the caller's accounts (soft delete). Repeat calls also return 204.
     *
     * @param id account ID
     * @return 204; 404 if missing or not owned
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        accountService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
