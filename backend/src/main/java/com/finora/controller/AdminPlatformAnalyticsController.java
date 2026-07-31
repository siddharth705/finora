package com.finora.controller;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminPlatformAnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide spend analytics for the admin console (frontend-admin/) -- top categories and
 *  top merchants by EXPENSE spend, aggregated across every user. See
 *  AdminPlatformAnalyticsService's class comment for why this needs a two-step aggregation.
 *  Gated by its own permission (PLATFORM_ANALYTICS_VIEW), separate from PLATFORM_STATS_VIEW's
 *  basic usage counts on AdminStatsController -- see V30's migration comment for why. */
@RestController
@RequestMapping("/api/v1/admin/analytics/platform")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminPlatformAnalyticsController {

    private final AdminPlatformAnalyticsService adminPlatformAnalyticsService;

    public AdminPlatformAnalyticsController(AdminPlatformAnalyticsService adminPlatformAnalyticsService) {
        this.adminPlatformAnalyticsService = adminPlatformAnalyticsService;
    }

    @GetMapping
    public ApiResponse<PlatformAnalyticsDto> analytics() {
        return ApiResponse.ok(adminPlatformAnalyticsService.platformAnalytics());
    }
}
