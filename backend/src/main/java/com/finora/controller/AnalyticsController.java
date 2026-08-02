package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.security.CurrentUser;
import com.finora.service.AnalyticsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/analytics/merchants?view=importStatistics -- the one self-service analytics view
 *  that survives here. The other five views this endpoint used to serve (topMerchants/trend/
 *  categoryConfidence/topCategories/learningGrowth) moved to AdminUserAnalyticsController when
 *  the self-service Analytics page was retired in favor of admin-only per-user analytics --
 *  importStatistics stays self-service because Settings.tsx's Account section still calls it
 *  directly for the logged-in user's own statement-import tiles. Kept the `view=` query-param
 *  shape (rather than collapsing to a plain GET) so that call site didn't need to change. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final CurrentUser currentUser;

    public AnalyticsController(AnalyticsService analyticsService, CurrentUser currentUser) {
        this.analyticsService = analyticsService;
        this.currentUser = currentUser;
    }

    @GetMapping("/merchants")
    public ApiResponse<?> merchants(@RequestParam String view) {
        return switch (view) {
            case "importStatistics" -> ApiResponse.ok(analyticsService.importStatistics(currentUser.id()));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unsupported view '" + view + "'. Supported views: importStatistics.");
        };
    }
}
