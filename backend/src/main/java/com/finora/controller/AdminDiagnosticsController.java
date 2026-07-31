package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DiagnosticsDto.PlatformDiagnosticsDto;
import com.finora.service.AdminDiagnosticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Diagnostics -- gated by PLATFORM_DIAGNOSTICS_VIEW (V34), a read-only permission
 * distinct from SYSTEM_SETTINGS: this page has no configuration-mutation power, so it shouldn't
 * require the same permission as actually changing platform settings or feature flags does. See
 * AdminDiagnosticsService/DiagnosticsDto for what this deliberately is and isn't.
 */
@RestController
@RequestMapping("/api/v1/admin/diagnostics")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminDiagnosticsController {

    private final AdminDiagnosticsService diagnosticsService;

    public AdminDiagnosticsController(AdminDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping
    public ApiResponse<PlatformDiagnosticsDto> overview() {
        return ApiResponse.ok(diagnosticsService.overview());
    }
}
