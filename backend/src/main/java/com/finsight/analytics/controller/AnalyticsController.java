package com.finsight.analytics.controller;

import com.finsight.analytics.dto.CategoryBreakdownDto;
import com.finsight.analytics.dto.MonthlyTrendDto;
import com.finsight.analytics.service.AnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spending analytics endpoints under {@code /api/v1/analytics} (T-37).
 *
 * <p>Read-only. No user ID is read from the request; {@link AnalyticsService} works on the
 * signed-in user's data only. Query parameters are strings so every invalid value is a
 * 400 ProblemDetail.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Spending per category for a month.
     *
     * @param year  optional, 2000–2100, together with {@code month}
     * @param month optional, 1–12, together with {@code year}; both absent means the current month
     * @return 200 with the breakdown; 400 for an invalid or incomplete period
     */
    @GetMapping("/category-breakdown")
    @PreAuthorize("isAuthenticated()")
    public CategoryBreakdownDto categoryBreakdown(@RequestParam(required = false) String year,
                                                  @RequestParam(required = false) String month) {
        return analyticsService.categoryBreakdown(year, month);
    }

    /**
     * Total spending for the current month and the five before it.
     *
     * @return 200 with six months, oldest first
     */
    @GetMapping("/monthly-trend")
    @PreAuthorize("isAuthenticated()")
    public MonthlyTrendDto monthlyTrend() {
        return analyticsService.monthlyTrend();
    }

    /**
     * Top five spending categories in the 30 days ending on {@code end}.
     *
     * @param end optional last day {@code YYYY-MM-DD}; absent means today
     * @return 200 with up to five categories; 400 for an invalid date
     */
    @GetMapping("/top-categories")
    @PreAuthorize("isAuthenticated()")
    public CategoryBreakdownDto topCategories(@RequestParam(required = false) String end) {
        return analyticsService.topCategories(end);
    }
}
