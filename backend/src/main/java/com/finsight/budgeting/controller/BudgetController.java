package com.finsight.budgeting.controller;

import com.finsight.budgeting.dto.BudgetDto;
import com.finsight.budgeting.dto.BudgetProgressDto;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.dto.UpdateBudgetRequest;
import com.finsight.budgeting.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Budget endpoints under {@code /api/v1/budgets} (design.md API table).
 *
 * <p>Input validation only; ownership and logic are in {@link BudgetService}. No user ID is read
 * from the request.
 */
@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * Lists the caller's budgets for the current period.
     *
     * @return 200 with the list
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<BudgetDto> list() {
        return budgetService.listCurrentPeriod();
    }

    /**
     * Creates a budget.
     *
     * @return 201 with the budget (no {@code Location}: the design has no single-budget GET)
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BudgetDto> create(@Valid @RequestBody CreateBudgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.create(request));
    }

    /**
     * Spending versus limit. {@code year} and {@code month} are strings so every invalid value is
     * reported as a 400 ProblemDetail by {@code PeriodParser}.
     *
     * @return 200 with the progress; 400 for invalid or incomplete period; 404 if not owned
     */
    @GetMapping("/{id}/progress")
    @PreAuthorize("isAuthenticated()")
    public BudgetProgressDto progress(@PathVariable Long id,
                                      @RequestParam(required = false) String year,
                                      @RequestParam(required = false) String month) {
        return budgetService.getProgress(id, year, month);
    }

    /**
     * Changes the monthly limit.
     *
     * @return 200 with the updated budget
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public BudgetDto update(@PathVariable Long id, @Valid @RequestBody UpdateBudgetRequest request) {
        return budgetService.updateLimit(id, request);
    }

    /**
     * Deletes a budget and its alerts.
     *
     * @return 204
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        budgetService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
