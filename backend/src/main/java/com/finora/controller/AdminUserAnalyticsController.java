package com.finora.controller;

import com.finora.dto.AnalyticsDto;
import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.service.AnalyticsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Support-assisted analytics for a specific user (PLATFORM_ANALYTICS_VIEW,
 * V30__platform_analytics_permission.sql -- reused rather than a new permission, since this is
 * the same "view analytics" capability AdminPlatformAnalyticsController already gates, just
 * scoped to one user instead of the whole platform). Reuses AnalyticsService.topMerchants/
 * merchantTrend/categoryConfidence/topCategories/learningGrowth exactly as the self-service
 * Analytics page did -- same DTOs -- just with the target userId sourced from the path instead of
 * CurrentUser. One method per concern (rather than the self-service controller's single
 * `view=` query-param dispatch) to match the rest of the admin surface's convention.
 *
 * importStatistics is deliberately NOT mirrored here -- it stays on the self-service
 * AnalyticsController, since Settings.tsx still calls it directly for the logged-in user's own
 * Account tiles (statements imported, transactions, last import date) and was never part of this
 * removal.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/analytics")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminUserAnalyticsController {

    private final AnalyticsService analyticsService;

    public AdminUserAnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/top-merchants")
    public ApiResponse<List<AnalyticsDto.TopMerchant>> topMerchants(@PathVariable UUID userId,
                                                                      @RequestParam(required = false) String month) {
        return ApiResponse.ok(analyticsService.topMerchants(userId, parseMonth(month)));
    }

    @GetMapping("/trend")
    public ApiResponse<List<AnalyticsDto.TrendPoint>> trend(@PathVariable UUID userId,
                                                              @RequestParam(required = false) String month) {
        return ApiResponse.ok(analyticsService.merchantTrend(userId, parseMonth(month)));
    }

    @GetMapping("/category-confidence")
    public ApiResponse<List<AnalyticsDto.CategoryConfidencePoint>> categoryConfidence(@PathVariable UUID userId) {
        return ApiResponse.ok(analyticsService.categoryConfidence(userId));
    }

    @GetMapping("/top-categories")
    public ApiResponse<List<AnalyticsDto.TopCategory>> topCategories(@PathVariable UUID userId,
                                                                       @RequestParam(required = false) String month) {
        return ApiResponse.ok(analyticsService.topCategories(userId, parseMonth(month)));
    }

    @GetMapping("/learning-growth")
    public ApiResponse<List<AnalyticsDto.LearningGrowthPoint>> learningGrowth(@PathVariable UUID userId) {
        return ApiResponse.ok(analyticsService.learningGrowth(userId));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format.");
        }
    }
}
