package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.analysis.StatementAnalysisReportService;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisDetail;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisSummary;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The read side of the analysis evidence table.
 *
 * <p>Sibling to {@code AdminLayoutIntelligenceController}, over a different source and answering a
 * different question. That one reports on {@code statement_imports} — documents that were parsed
 * AND confirmed — so it can only ever describe successes. This one reports on every upload
 * attempt, which is where the failures live, and failures are what parser work is aimed at.
 *
 * <h2>Gating and privacy</h2>
 * PLATFORM_DIAGNOSTICS_VIEW, same as its sibling: engineering telemetry about pipeline
 * performance, granting no ability to change anything. Responses carry no file name and no user
 * id — see {@link StatementAnalysisReportService} for why the {@code reference} is the handle
 * instead.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/analyses")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminStatementAnalysisController {

    private static final int DEFAULT_LIMIT = 50;

    private final StatementAnalysisReportService reportService;

    public AdminStatementAnalysisController(StatementAnalysisReportService reportService) {
        this.reportService = reportService;
    }

    /** Recent upload attempts, newest first, successes and failures together. */
    @GetMapping
    public ApiResponse<List<AnalysisView>> recent(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ApiResponse.ok(reportService.recent(limit));
    }

    /**
     * The engine at a glance: how much was read, how much could not be anchored, and why.
     *
     * <p>The reason histogram is the operative field. One reason dominating across many documents
     * describes a missing capability; the same reason confined to one document describes that
     * document.
     */
    @GetMapping("/summary")
    public ApiResponse<AnalysisSummary> summary() {
        return ApiResponse.ok(reportService.summary());
    }

    /**
     * One analysis by its quotable handle, e.g. {@code SA-20260806-0145}, with what is already
     * known about its layout — so opening it does not mean rediscovering that this fingerprint has
     * defeated the parser eleven times before.
     */
    @GetMapping("/{reference}")
    public ApiResponse<AnalysisDetail> byReference(@PathVariable String reference) {
        return ApiResponse.ok(reportService.detailByReference(reference)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
}
