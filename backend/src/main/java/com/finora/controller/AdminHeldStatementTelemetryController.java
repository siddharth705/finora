package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.HeldStatementTelemetryDto;
import com.finora.service.HeldStatementTelemetryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The aggregate readout over trust-review holds. Same gate and the same reasoning as {@code
 * AdminImportTelemetryController}: {@code PLATFORM_DIAGNOSTICS_VIEW}, not {@code
 * TRUST_REVIEW_MANAGE} -- this is engineering telemetry about how the pipeline behaves, not access
 * to any customer's hold. The response is counts only: no held id, no user id, no bank name tied
 * to an individual row. See Plan 4's own Decisions table.
 *
 * <p>A distinct {@code @RestController} from {@link AdminHeldStatementController}, mapped one
 * level under the same base path -- {@code /telemetry} is a literal path segment, so it does not
 * collide with that controller's {@code /{heldId}} pattern (verified directly with a real request
 * in {@code AdminHeldStatementTelemetryControllerIT}, not assumed from reading the two mappings).
 */
@RestController
@RequestMapping("/api/v1/admin/held-statements/telemetry")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminHeldStatementTelemetryController {

    private final HeldStatementTelemetryService telemetryService;

    public AdminHeldStatementTelemetryController(HeldStatementTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping
    public ApiResponse<HeldStatementTelemetryDto> summary() {
        return ApiResponse.ok(telemetryService.summary());
    }
}
