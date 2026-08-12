package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.*;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.security.CurrentUser;
import com.finora.imports.ImportService;
import com.finora.imports.StatementUpload;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    // How many recent failures GET /failures returns. A fixed recent-window cap rather than real
    // pagination -- Premium Import Reliability v1 §2.1 scopes this as "enough to render a list",
    // the same bar ImportSessionSummaryDto's sibling endpoint already sets; a paginated failure
    // history is a later, separate concern if it turns out to be needed.
    private static final int RECENT_FAILURES_LIMIT = 20;

    private final ImportService importService;
    private final ImportSessionService importSessionService;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;
    private final StatementAnalysisRecorder analysisRecorder;

    public ImportController(ImportService importService, ImportSessionService importSessionService,
                             ImportConcurrencyLimiter concurrencyLimiter, CurrentUser currentUser,
                             StatementAnalysisRecorder analysisRecorder) {
        this.importService = importService;
        this.importSessionService = importSessionService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
        this.analysisRecorder = analysisRecorder;
    }

    // ADR-0002: staging now persists the reviewed-later state server-side, so a dropped session
    // doesn't lose the whole upload+parse. Returns the session id alongside the same staging
    // payload as before.
    //
    // Gated through ImportConcurrencyLimiter -- see that class's own doc comment for the full
    // reasoning. This is the actual CPU/DB-heavy work (parsing, categorization, duplicate
    // detection), so it's the one that needs bounding under a burst, not every endpoint in this
    // controller.
    @PostMapping(value = "/csv/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<StagingSessionResponse>> stage(@RequestParam("file") MultipartFile file) throws Exception {
        // Before the limiter, deliberately: rejecting an empty file or a PDF posted to the CSV
        // endpoint should not consume one of the six permits the expensive work is queued behind.
        StatementUpload.requireReadable(file, StatementUpload.Format.CSV);
        return ResponseEntity.ok(ApiResponse.ok(
                concurrencyLimiter.runGated(() -> importService.parseAndStageWithSession(currentUser.id(), file))));
    }

    // PDF Milestone 1 (com.finora.imports.pdf) -- digital/text-based bank statements only, no
    // OCR/scanned PDFs yet. Everything below this staging call (confirm, sessions list/get/
    // delete) is completely unaware whether a session came from here or from /csv/stage above --
    // both produce the identical StagingSessionResponse/ImportSession shape, so nothing else in
    // this controller needed to change for PDF support.
    //
    // Response shape changed from StagingSessionResponse to PdfStagingSessionResponse to carry a
    // multi-account PDF's several detected sections (e.g. HSBC's composite statement, which
    // bundles a savings-account section and a credit-card section in one file) -- the
    // single-account case (multiAccount: false) still carries the exact same `staging` payload
    // this endpoint always returned, just wrapped in the new envelope.
    //
    // `password` is optional and travels in the multipart BODY, never as a query parameter -- a
    // document password in a URL would be captured by access logs, browser history and referrers.
    // It is used to open the document and then discarded; it is not stored on the ImportSession
    // and not logged. Clients that never send it behave exactly as they did before.
    @PostMapping(value = "/pdf/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PdfStagingSessionResponse>> stagePdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws Exception {
        StatementUpload.requireReadable(file, StatementUpload.Format.PDF);
        return ResponseEntity.ok(ApiResponse.ok(
                concurrencyLimiter.runGated(() -> importService.parseAndStagePdfWithSession(currentUser.id(), file, password))));
    }

    // ADR-0002: plain JSON now, not multipart -- the file no longer needs to be re-uploaded here,
    // since it's already persisted on the ImportSession from staging (looked up via
    // request.sessionId()).
    @PostMapping("/csv/confirm")
    public ResponseEntity<ApiResponse<ConfirmResponse>> confirm(@RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(importService.confirmSession(currentUser.id(), request), "Import complete"));
    }

    // Confirms every account section of a multi-account PDF staging session together (see
    // ImportService.confirmMultiSection) -- used only when /pdf/stage returned multiAccount: true.
    @PostMapping("/pdf/confirm-multi")
    public ResponseEntity<ApiResponse<MultiAccountConfirmResponse>> confirmMulti(@RequestBody MultiAccountConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(importService.confirmMultiSection(currentUser.id(), request), "Import complete"));
    }

    // ADR-0002: "your unfinished imports" -- lets the frontend offer to resume a staged-but-not-
    // yet-confirmed session instead of it silently existing only until it expires.
    @GetMapping("/sessions")
    public ApiResponse<List<ImportSessionSummaryDto>> listSessions() {
        List<ImportSessionSummaryDto> sessions = importSessionService.listActiveSessions(currentUser.id()).stream()
                .map(this::toSummary)
                .toList();
        return ApiResponse.ok(sessions);
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<StagingSessionResponse> getSession(@PathVariable UUID id) {
        ImportSession session = importSessionService.getOwnedSession(currentUser.id(), id);
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        int dupCount = (int) rows.stream().filter(StagedRow::likelyDuplicate).count();
        // unparseableRows is intentionally NOT persisted on ImportSession (v1 scope -- see
        // docs/engineering/financial-document-intelligence-principles.md's "Never lose
        // information" section) -- a resumed session shows the rows that DID stage correctly
        // exactly as before, but a row that failed to parse is only visible in the original
        // staging response, not after a later resume. Accepted trade-off, not an oversight.
        StagingResponse staging = new StagingResponse(rows, rows.size(), dupCount, importSessionService.readDetectedAccount(session), List.of());
        return ApiResponse.ok(new StagingSessionResponse(session.getId(), staging));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable UUID id) {
        importSessionService.deleteSession(currentUser.id(), id);
        return ApiResponse.ok(null, "Import session discarded");
    }

    // Premium Import Reliability v1, §2.1: "your recent failed imports" -- a document that never
    // got far enough to become an ImportSession at all (a scanned PDF, no header found, zero
    // transactions extracted) previously left no trace the customer who uploaded it could ever see
    // again. ImportService.recordParseFailure already writes this row on every sync-path failure,
    // customer and admin; this endpoint is the first thing that reads it back for the customer who
    // owns it, filtered to their own CUSTOMER_IMPORT failures only -- never another user's rows,
    // and never an ADMIN_ANALYSIS probe even if the same user happens to also be an admin.
    @GetMapping("/failures")
    public ApiResponse<List<ImportFailureSummaryDto>> listFailures() {
        return ApiResponse.ok(analysisRecorder.recentCustomerFailures(currentUser.id(), RECENT_FAILURES_LIMIT));
    }

    private ImportSessionSummaryDto toSummary(ImportSession session) {
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        return new ImportSessionSummaryDto(session.getId(), session.getFileName(), rows.size(),
                session.getCreatedAt(), session.getExpiresAt());
    }
}
