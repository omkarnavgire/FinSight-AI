package com.finsight.cashflow.controller;

import com.finsight.cashflow.dto.ForecastDto;
import com.finsight.cashflow.service.CashFlowService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cash-flow endpoints under {@code /api/v1/cashflow} (T-43).
 *
 * <p>No user ID is read from the request; {@link CashFlowService} works on the signed-in user only.
 * {@code horizon} is a string so every invalid value is a 400 ProblemDetail.
 */
@RestController
@RequestMapping("/api/v1/cashflow")
public class CashFlowController {

    private final CashFlowService cashFlowService;

    public CashFlowController(CashFlowService cashFlowService) {
        this.cashFlowService = cashFlowService;
    }

    /**
     * The caller's forecast for one horizon. Calculated and stored first if none exists yet.
     *
     * @param horizon optional {@code 30} (default), {@code 60} or {@code 90}
     * @return 200 with the forecast; 400 for any other horizon
     */
    @GetMapping("/forecast")
    @PreAuthorize("isAuthenticated()")
    public ForecastDto forecast(@RequestParam(required = false) String horizon) {
        return cashFlowService.getForecast(horizon);
    }

    /**
     * Recalculates all three horizons now. At most once per hour per user.
     *
     * @return 200 with the 30-, 60- and 90-day forecasts; 429 with {@code Retry-After} when called again too soon
     */
    @PostMapping("/refresh")
    @PreAuthorize("isAuthenticated()")
    public List<ForecastDto> refresh() {
        return cashFlowService.refresh();
    }
}
