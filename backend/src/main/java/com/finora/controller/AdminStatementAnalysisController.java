package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.FailureCountDto;
import com.finora.dto.ImportDto.ImportFailureSummaryDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.AdminAnalysisService;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisReportService;
import com.finora.security.CurrentUser;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisDetail;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisSummary;
import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisView;
import com.finora.service.IdentityLookup;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
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
 * performance, granting no ability to change anything. The engine-diagnostics endpoints below
 * (recent/summary/byReference) carry no file name and no user id — see
 * {@link StatementAnalysisReportService} for why the {@code reference} is the handle instead. The
 * two failure-analytics endpoints (Premium Import Reliability v1, §4) are a deliberate, narrower
 * exception to that: {@code failuresByUser} exists specifically to look up ONE identified
 * customer's own file names, because that is what a support investigation is for.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/analyses")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminStatementAnalysisController {

    private static final int DEFAULT_LIMIT = 50;

    private final StatementAnalysisReportService reportService;
    private final AdminAnalysisService adminAnalysisService;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;
    private final StatementAnalysisRecorder recorder;
    private final IdentityLookup identityLookup;

    public AdminStatementAnalysisController(StatementAnalysisReportService reportService,
                                            AdminAnalysisService adminAnalysisService,
                                            ImportConcurrencyLimiter concurrencyLimiter,
                                            CurrentUser currentUser,
                                            StatementAnalysisRecorder recorder,
                                            IdentityLookup identityLookup) {
        this.reportService = reportService;
        this.adminAnalysisService = adminAnalysisService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
        this.recorder = recorder;
        this.identityLookup = identityLookup;
    }

    /**
     * Put a document through the engine without importing anything.
     *
     * <p>No customer, no account, no transactions, no stored file — see {@link AdminAnalysisService}
     * for how "nothing is written" is actually enforced, which is less obvious than it sounds
     * because the staging path creates merchant rows as it parses.
     *
     * <h2>Why this needs its own permission</h2>
     * Everything else on this controller is a report and sits behind the class-level, explicitly
     * read-only PLATFORM_DIAGNOSTICS_VIEW. This one accepts an upload, spends real CPU and writes
     * an evidence row, so it carries ENGINE_ANALYSIS_RUN (V61) instead. Method-level
     * {@code @PreAuthorize} replaces the class-level rule rather than adding to it, which is the
     * intent: viewing the reports and running the engine are separately grantable.
     *
     * <p>A parse failure comes back 200 with a FAILED analysis, not an HTTP error. Documents the
     * engine cannot read are the main reason to open this page, and turning those into an error
     * response would give an admin a red toast and no link to the evidence.
     *
     * <p>{@code password} travels in the multipart body, never as a query parameter — a document
     * password in a URL is captured by access logs, browser history and referrer headers. It opens
     * the document and is then discarded: not stored, not logged, not part of the analysis row.
     */
    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('ENGINE_ANALYSIS_RUN')")
    public ApiResponse<AnalysisDetail> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws Exception {
        // Gated for the same reason /imports/csv/stage is: this is the CPU-heavy path, and an
        // admin analysing a 39-page statement should queue behind the same bound as everyone else
        // rather than competing with customer imports for the whole thread pool.
        String reference = concurrencyLimiter.runGated(
                () -> adminAnalysisService.analyze(currentUser.id(), file, password));

        // Read back rather than assembled here, so an analysis looks identical whether it arrived
        // through this endpoint or through a customer upload -- one shape, one code path, no
        // chance of the two drifting into disagreeing about the same row.
        return ApiResponse.ok(reportService.detailByReference(reference)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "The document was analysed but the analysis could not be read back.")));
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

    /**
     * How many customer imports failed, by reason, since {@code since} -- Premium Import
     * Reliability v1, §4. "How many imports failed today", made answerable without opening a SQL
     * client: {@code since=2026-08-12T00:00:00Z} for today, an arbitrary window for "since this
     * release", "since parser version X" -- whatever the caller means by it, they have to say so.
     *
     * <p>No default window on purpose: an unbounded scan of a table that only grows is a cost this
     * endpoint should never silently absorb just because a caller omitted a parameter.
     */
    @GetMapping("/failures/summary")
    public ApiResponse<List<FailureCountDto>> failureSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return ApiResponse.ok(reportService.failureCounts(since));
    }

    /**
     * One customer's own recent failed imports, looked up by email -- the support half of Premium
     * Import Reliability v1, §4. A support conversation starts with an email address, essentially
     * never the internal user id, so this resolves the email first (scoped to {@code SCOPE_USER}
     * -- support is investigating a customer, not an admin account) and then reuses {@link
     * StatementAnalysisRecorder#recentCustomerFailures} exactly as the customer's own {@code GET
     * /import/failures} does -- same DTO, same {@code CUSTOMER_IMPORT}-only/{@code failureDetail}
     * -excluded boundary, so this view can never show support anything the customer endpoint's own
     * privacy rules wouldn't already allow.
     *
     * <p>{@link IdentityLookup#byEmail} already fails closed to "no match" on a case-duplicate
     * row rather than throwing, so the only not-found case reaching this method's own
     * {@code orElseThrow} is a genuinely absent account.
     */
    @GetMapping("/failures/by-user")
    public ApiResponse<List<ImportFailureSummaryDto>> failuresByUser(
            @RequestParam String email,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        User user = identityLookup.byEmail(email, User.SCOPE_USER)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No customer account matches that email."));
        return ApiResponse.ok(recorder.recentCustomerFailures(user.getId(), limit));
    }
}
