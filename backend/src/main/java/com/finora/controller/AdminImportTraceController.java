package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.trace.ImportTraceDto;
import com.finora.imports.trace.ImportTraceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * One import, end to end, in one request.
 *
 * <p>Milestone 2's sixth success criterion: <i>an administrator can trace one import from upload
 * through parsing, verification, learning and completion in a single view, without a log or an
 * engineer.</i> Until now that meant three queries against three tables plus knowing all three
 * existed — {@code import_jobs} for progress, {@code statement_analysis_sessions} for what the
 * parser saw, {@code merchant_learning_events} for what it taught the system — and nothing joined
 * them.
 *
 * <h2>Two routes, not one guessing route</h2>
 *
 * <p>An analysis reference and a job id are distinguishable by shape, so one path variable could
 * have served both. Two explicit routes instead: a route that inspects its argument to decide what
 * it means is a route that can decide wrong, and the failure would arrive as an unhelpful 404 rather
 * than as a wrong URL. Both return the same shape, so a caller that has one handle never needs the
 * other.
 *
 * <h2>Gating and privacy</h2>
 *
 * <p>{@code PLATFORM_DIAGNOSTICS_VIEW}, the same read-only permission its siblings
 * {@code AdminStatementAnalysisController} and {@code AdminLayoutIntelligenceController} carry. This
 * grants no ability to change anything, and the response carries no file name, no user id, no
 * merchant and no statement content — see {@link ImportTraceDto} for the boundary and why the
 * reference is the handle instead.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/traces")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminImportTraceController {

    private final ImportTraceService traceService;

    public AdminImportTraceController(ImportTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * The trace for an upload, by the handle support quotes — {@code SA-20260806-0145}.
     *
     * <p>The entry point for almost every real question, because the reference is what a customer
     * conversation produces. The job id route below is for the case that starts from the queue
     * instead.
     */
    @GetMapping("/by-analysis/{reference}")
    public ApiResponse<ImportTraceDto.Trace> byAnalysis(@PathVariable String reference) {
        return ApiResponse.ok(traceService.byAnalysisReference(reference)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    /** The same trace, for an upload that went through the asynchronous import queue. */
    @GetMapping("/by-job/{jobId}")
    public ApiResponse<ImportTraceDto.Trace> byJob(@PathVariable UUID jobId) {
        return ApiResponse.ok(traceService.byJobId(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
}
