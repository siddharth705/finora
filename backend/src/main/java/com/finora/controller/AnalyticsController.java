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

import java.time.YearMonth;

/** GET /api/v1/analytics/merchants?view=...&month=YYYY-MM -- one endpoint, one query param
 *  selecting which AnalyticsService method backs the response, per spec §5.7/§8 ("not eight
 *  separate endpoints"). Kept as one endpoint for the three new Workspace Analytics views too
 *  (topCategories/importStatistics/learningGrowth) rather than starting a second convention --
 *  same reasoning, same shape, just more views behind the one switch. "merchants" in the path is
 *  now a slight misnomer (not every view is merchant-scoped) but renaming a stable, already-
 *  shipped route for that alone isn't worth the churn -- see AnalyticsService's own doc comment
 *  for why rule usage isn't among the views added here. */
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
    public ApiResponse<?> merchants(@RequestParam String view, @RequestParam(required = false) String month) {
        YearMonth parsedMonth = parseMonth(month);
        return switch (view) {
            case "topMerchants" -> ApiResponse.ok(analyticsService.topMerchants(currentUser.id(), parsedMonth));
            case "trend" -> ApiResponse.ok(analyticsService.merchantTrend(currentUser.id(), parsedMonth));
            case "categoryConfidence" -> ApiResponse.ok(analyticsService.categoryConfidence(currentUser.id()));
            case "topCategories" -> ApiResponse.ok(analyticsService.topCategories(currentUser.id(), parsedMonth));
            case "importStatistics" -> ApiResponse.ok(analyticsService.importStatistics(currentUser.id()));
            case "learningGrowth" -> ApiResponse.ok(analyticsService.learningGrowth(currentUser.id()));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unsupported view '" + view + "'. Supported views: topMerchants, trend, categoryConfidence, "
                            + "topCategories, importStatistics, learningGrowth.");
        };
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format.");
        }
    }
}
