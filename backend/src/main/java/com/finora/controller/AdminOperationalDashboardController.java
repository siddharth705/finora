package com.finora.controller;

import com.finora.dto.AdminDtos.ActivationFunnelDto;
import com.finora.dto.AdminDtos.OperationalDashboardDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminOperationalDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Operational Dashboard -- "Is Finora healthy?" as one screen. Reuses PLATFORM_STATS_VIEW
 *  (AdminStatsController's existing permission) rather than minting a new one: this is the same
 *  "view platform-wide numbers" capability, just a richer view of it, not a separate concern.
 *  See AdminOperationalDashboardService for what's actually aggregated. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasAuthority('PLATFORM_STATS_VIEW')")
public class AdminOperationalDashboardController {

    private final AdminOperationalDashboardService adminOperationalDashboardService;

    public AdminOperationalDashboardController(AdminOperationalDashboardService adminOperationalDashboardService) {
        this.adminOperationalDashboardService = adminOperationalDashboardService;
    }

    @GetMapping("/overview")
    public ApiResponse<OperationalDashboardDto> overview() {
        return ApiResponse.ok(adminOperationalDashboardService.overview());
    }

    /** D-27 PR3-D. */
    @GetMapping("/activation-funnel")
    public ApiResponse<ActivationFunnelDto> activationFunnel() {
        return ApiResponse.ok(adminOperationalDashboardService.activationFunnel());
    }
}
