package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DashboardSummaryDto;
import com.finora.dto.FinancialJourneyDto;
import com.finora.security.CurrentUser;
import com.finora.service.DashboardService;
import com.finora.service.FinancialJourneyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final FinancialJourneyService financialJourneyService;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService dashboardService, FinancialJourneyService financialJourneyService,
                                CurrentUser currentUser) {
        this.dashboardService = dashboardService;
        this.financialJourneyService = financialJourneyService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> summary() {
        return ApiResponse.ok(dashboardService.summarize(currentUser.id()));
    }

    @GetMapping("/journey")
    public ApiResponse<FinancialJourneyDto> journey() {
        return ApiResponse.ok(financialJourneyService.build(currentUser.id()));
    }
}
