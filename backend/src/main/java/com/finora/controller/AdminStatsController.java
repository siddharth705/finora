package com.finora.controller;

import com.finora.dto.AdminDtos.PlatformStatsDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminStatsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide usage stats for the admin Dashboard (frontend-admin/). See AdminStatsService
 *  for what's actually computed and why this is gated separately from AUDIT_VIEW/USER_VIEW. */
@RestController
@RequestMapping("/api/v1/admin/stats")
@PreAuthorize("hasAuthority('PLATFORM_STATS_VIEW')")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    public AdminStatsController(AdminStatsService adminStatsService) {
        this.adminStatsService = adminStatsService;
    }

    @GetMapping("/overview")
    public ApiResponse<PlatformStatsDto> overview() {
        return ApiResponse.ok(adminStatsService.overview());
    }
}
