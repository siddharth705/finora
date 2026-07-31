package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.*;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportSessionService;
import com.finora.security.CurrentUser;
import com.finora.imports.ImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    private final ImportService importService;
    private final ImportSessionService importSessionService;
    private final CurrentUser currentUser;

    public ImportController(ImportService importService, ImportSessionService importSessionService, CurrentUser currentUser) {
        this.importService = importService;
        this.importSessionService = importSessionService;
        this.currentUser = currentUser;
    }

    // ADR-0002: staging now persists the reviewed-later state server-side, so a dropped session
    // doesn't lose the whole upload+parse. Returns the session id alongside the same staging
    // payload as before.
    @PostMapping(value = "/csv/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<StagingSessionResponse>> stage(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(ApiResponse.ok(importService.parseAndStageWithSession(currentUser.id(), file)));
    }

    // PDF Milestone 1 (com.finora.imports.pdf) -- digital/text-based bank statements only, no
    // OCR/scanned PDFs yet. Everything below this staging call (confirm, sessions list/get/
    // delete) is completely unaware whether a session came from here or from /csv/stage above --
    // both produce the identical StagingSessionResponse/ImportSession shape, so nothing else in
    // this controller needed to change for PDF support.
    @PostMapping(value = "/pdf/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<StagingSessionResponse>> stagePdf(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(ApiResponse.ok(importService.parseAndStagePdfWithSession(currentUser.id(), file)));
    }

    // ADR-0002: plain JSON now, not multipart -- the file no longer needs to be re-uploaded here,
    // since it's already persisted on the ImportSession from staging (looked up via
    // request.sessionId()).
    @PostMapping("/csv/confirm")
    public ResponseEntity<ApiResponse<ConfirmResponse>> confirm(@RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(importService.confirmSession(currentUser.id(), request), "Import complete"));
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
        StagingResponse staging = new StagingResponse(rows, rows.size(), dupCount, importSessionService.readDetectedAccount(session));
        return ApiResponse.ok(new StagingSessionResponse(session.getId(), staging));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable UUID id) {
        importSessionService.deleteSession(currentUser.id(), id);
        return ApiResponse.ok(null, "Import session discarded");
    }

    private ImportSessionSummaryDto toSummary(ImportSession session) {
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        return new ImportSessionSummaryDto(session.getId(), session.getFileName(), rows.size(),
                session.getCreatedAt(), session.getExpiresAt());
    }
}
