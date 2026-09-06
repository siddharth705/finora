package com.finora.controller;

import com.finora.dto.AnalyticsDto;
import com.finora.dto.ApiResponse;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.security.CurrentUser;
import com.finora.service.AnalyticsService;
import com.finora.service.EntitlementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/** GET /api/v1/analytics/merchants?view=importStatistics stays open to every plan -- Settings.tsx's
 *  Account section calls it directly for the logged-in user's own statement-import tiles, and it
 *  was never part of the self-service Analytics page this class otherwise restores below.
 *
 *  <p>The other five views (topMerchants/trend/categoryConfidence/topCategories/learningGrowth)
 *  were retired to admin-only ({@link AdminUserAnalyticsController}) when self-service Analytics
 *  was pulled -- see that class's doc comment. This is where they come back as a real, paid
 *  feature: the first live call site for {@link EntitlementService#hasEntitlement}, gated on
 *  {@link FeatureEntitlement#ADVANCED_REPORTS}. Every other seeded {@code FeatureEntitlement} key
 *  still enforces nothing anywhere in the backend; this endpoint is the one exception. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public AnalyticsController(AnalyticsService analyticsService, EntitlementService entitlementService,
                                CurrentUser currentUser) {
        this.analyticsService = analyticsService;
        this.entitlementService = entitlementService;
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

    @GetMapping("/top-merchants")
    public ApiResponse<List<AnalyticsDto.TopMerchant>> topMerchants(@RequestParam(required = false) String month) {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.topMerchants(currentUser.id(), parseMonth(month)));
    }

    @GetMapping("/trend")
    public ApiResponse<List<AnalyticsDto.TrendPoint>> trend(@RequestParam(required = false) String month) {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.merchantTrend(currentUser.id(), parseMonth(month)));
    }

    @GetMapping("/category-confidence")
    public ApiResponse<List<AnalyticsDto.CategoryConfidencePoint>> categoryConfidence() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.categoryConfidence(currentUser.id()));
    }

    @GetMapping("/top-categories")
    public ApiResponse<List<AnalyticsDto.TopCategory>> topCategories(@RequestParam(required = false) String month) {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.topCategories(currentUser.id(), parseMonth(month)));
    }

    @GetMapping("/learning-growth")
    public ApiResponse<List<AnalyticsDto.LearningGrowthPoint>> learningGrowth() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.learningGrowth(currentUser.id()));
    }

    /** Checked per request, not cached -- same "no cache" posture EntitlementService's own class
     *  doc already commits to (an admin's manual plan change, or a Razorpay upgrade webhook, takes
     *  effect on the caller's very next request). Deliberately checks the CALLER's own entitlement
     *  only: unlike {@link AdminUserAnalyticsController}, which reads any user's analytics on a
     *  support agent's behalf regardless of that user's plan, this is a self-service endpoint --
     *  there is no "other user" to leak. */
    private void requireAdvancedReports() {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.ADVANCED_REPORTS)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
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
