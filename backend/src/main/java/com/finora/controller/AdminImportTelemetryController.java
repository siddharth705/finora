package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportTelemetryDto;
import com.finora.service.AdminImportTelemetryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the import trust telemetry has observed so far, in aggregate.
 *
 * <p>Phase 0 (V141) started recording the evidence every import already computes. This is the only
 * thing that reads it. That matters more than it sounds: {@code
 * ImportVerificationRecorder.recordForJob} sat dead for months precisely because nothing consumed
 * its output, so nobody could notice it was never called. A signal with no reader is a signal
 * nobody can tell is broken.
 *
 * <p><b>What this is for.</b> Calibration, before any gating exists. The question it answers is
 * "if a trust rule fired on some threshold, how often would that be, and on what?" -- which today
 * has no answer, which is why every candidate threshold would be a guess.
 *
 * <h2>Gating and privacy</h2>
 *
 * <p>{@code PLATFORM_DIAGNOSTICS_VIEW}, the same read-only permission its siblings
 * {@code AdminImportTraceController} and {@code AdminStatementAnalysisController} carry, and for
 * the same reason: this is engineering telemetry about how the pipeline behaves, not access to
 * anyone's statements. Deliberately not {@code IMPORT_TRIAGE_MANAGE} -- that permission exists
 * because triage reaches a named customer's document and its raw parser error, and nothing here
 * does. The response is counts only: no user, no job id, no file name, no statement content, and
 * nothing scoped to an individual.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/telemetry")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminImportTelemetryController {

    private final AdminImportTelemetryService telemetryService;

    public AdminImportTelemetryController(AdminImportTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * The distribution, its denominator, and what is excluded from it.
     *
     * <p>Returns counts rather than percentages on purpose -- see the service's class doc. The
     * caller gets {@code withTelemetry} next to the numerators so any rate it derives is one it
     * chose the denominator for knowingly.
     */
    @GetMapping("/summary")
    public ApiResponse<ImportTelemetryDto.Summary> summary() {
        return ApiResponse.ok(telemetryService.summary());
    }
}
