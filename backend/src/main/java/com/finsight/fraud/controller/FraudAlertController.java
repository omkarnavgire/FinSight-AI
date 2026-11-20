package com.finsight.fraud.controller;

import com.finsight.fraud.dto.FraudAlertDto;
import com.finsight.fraud.service.FraudAlertService;
import com.finsight.shared.pagination.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fraud alert endpoints under {@code /api/v1/fraud/alerts} (T-53, design.md API table).
 *
 * <p>Input validation only; ownership and logic are in {@link FraudAlertService}. No user ID is read
 * from the request.
 */
@RestController
@RequestMapping("/api/v1/fraud/alerts")
public class FraudAlertController {

    private final FraudAlertService alertService;

    public FraudAlertController(FraudAlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Lists the caller's active (unacknowledged) alerts, newest first.
     *
     * @return 200 with one page; 400 for an invalid page or size
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<FraudAlertDto> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return alertService.listActive(page, size);
    }

    /**
     * Dismisses one of the caller's alerts. Repeating it is safe.
     *
     * @return 200 with the alert; 404 if missing or not owned
     */
    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("isAuthenticated()")
    public FraudAlertDto acknowledge(@PathVariable Long id) {
        return alertService.acknowledge(id);
    }
}
