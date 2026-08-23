package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.IntegrationsDto.IntegrationsOverviewDto;
import com.finora.service.AdminIntegrationsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integrations page for the admin portal -- which third-party services Finora talks to, their
 *  live configured/unconfigured status, and which integrations are planned but not yet built.
 *  Shares PLATFORM_DIAGNOSTICS_VIEW with its siblings (AdminSystemController, AdminDiagnostics
 *  Controller): this is the same read-only operational-visibility capability, just a different
 *  real signal -- see AdminSystemController's own doc comment for why that permission was split
 *  out from SYSTEM_SETTINGS in the first place. */
@RestController
@RequestMapping("/api/v1/admin/integrations")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminIntegrationsController {

    private final AdminIntegrationsService adminIntegrationsService;

    public AdminIntegrationsController(AdminIntegrationsService adminIntegrationsService) {
        this.adminIntegrationsService = adminIntegrationsService;
    }

    @GetMapping
    public ApiResponse<IntegrationsOverviewDto> overview() {
        return ApiResponse.ok(adminIntegrationsService.overview());
    }
}
