package com.finora.controller;

import com.finora.dto.AdminDtos.RecentImportDto;
import com.finora.dto.AdminDtos.SystemHealthDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminSystemService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** System health panel for the admin portal -- gated by PLATFORM_DIAGNOSTICS_VIEW (V34), split
 *  out from SYSTEM_SETTINGS: this controller is purely read-only (health/uptime/recent imports),
 *  and bundling it with SYSTEM_SETTINGS meant anyone who could look at it also necessarily had
 *  the power to mutate platform-wide config (PlatformSettingsController, AdminFeatureFlagController
 *  -- both remain on SYSTEM_SETTINGS, since mutating config is exactly what that permission
 *  describes). See AdminSystemService for why this wraps Actuator's HealthEndpoint bean directly
 *  instead of proxying the public, detail-suppressed /actuator/health endpoint.
 *
 *  GET /recent-imports (Admin Portal Phase 7) shares this permission gate rather than a new one
 *  -- it's the same read-only operational-visibility capability as the health panel, just a
 *  different real signal. Platform Diagnostics (AdminDiagnosticsController) shares this same
 *  permission too, deliberately -- both are the same class of capability. */
@RestController
@RequestMapping("/api/v1/admin/system")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminSystemController {

    private final AdminSystemService adminSystemService;

    public AdminSystemController(AdminSystemService adminSystemService) {
        this.adminSystemService = adminSystemService;
    }

    @GetMapping("/health")
    public ApiResponse<SystemHealthDto> health() {
        return ApiResponse.ok(adminSystemService.health());
    }

    @GetMapping("/recent-imports")
    public ApiResponse<List<RecentImportDto>> recentImports() {
        return ApiResponse.ok(adminSystemService.recentImports());
    }
}
