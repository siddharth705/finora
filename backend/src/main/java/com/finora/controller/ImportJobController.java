package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.StatementUpload;
import com.finora.imports.jobs.ImportJobDto;
import com.finora.imports.jobs.ImportJobService;
import com.finora.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The asynchronous import path: accept an upload, return immediately, work later.
 *
 * <h2>Why this is a new endpoint rather than a change to the existing one</h2>
 *
 * <p>{@code POST /import/csv/stage} returns {@code 200 {sessionId, staging}} and both the web app
 * and the mobile app read those fields. Changing it to {@code 202 {jobId}} removes every field a
 * client reads and changes the response type — two entries on the breaking list in
 * {@code docs/engineering/api-compatibility-policy.md}, which requires {@code /api/v2} for either.
 * The mobile support window obliges the backend to keep the last two released app versions working,
 * and an installed app cannot be updated in step with a deploy.
 *
 * <p>Adding an endpoint is explicitly non-breaking in the same document. So the synchronous path
 * stays exactly as it is and this runs beside it, until the frontend adopts polling (phase 3 of
 * {@code enterprise-scale-milestone-design.md}) and the old route can be deprecated properly.
 *
 * <h2>This path is opt-in per environment</h2>
 *
 * <p>It needs object storage configured, because the worker runs later in another thread and has
 * nothing to read but a content address. With no provider it returns 503 with a message naming the
 * missing configuration, rather than accepting an upload that is certain to fail.
 *
 * <p>Two settings gate it and both default to off. {@code app.import.queue.enabled} decides whether
 * the worker runs at all, and {@code app.statement-storage.provider} decides whether it has anything
 * to read. {@code ProductionConfigValidator} refuses to start a deployment that sets the first
 * without the second, rather than letting every upload here fail with 503 at runtime.
 *
 * <p><b>Replay safety is no longer what holds this back.</b> Phase 2 landed in {@code V67} as two
 * partial unique indexes — {@code statement_imports.import_job_id} and
 * {@code transactions (statement_import_id, row_ordinal)} — so a replayed job is a rejected write
 * rather than a statement imported twice. What remains is scope rather than correctness:
 * {@code ImportJobWorker} takes a job as far as {@code ANALYZING} and stops there, so the review and
 * the confirm are still synchronous requests the user makes against the staged session.
 */
@RestController
@RequestMapping("/api/v1/import/jobs")
public class ImportJobController {

    private final ImportJobService importJobService;
    private final CurrentUser currentUser;

    public ImportJobController(ImportJobService importJobService, CurrentUser currentUser) {
        this.importJobService = importJobService;
        this.currentUser = currentUser;
    }

    /**
     * Accepts a statement and returns 202 with somewhere to poll.
     *
     * <p>Deliberately NOT gated through {@code ImportConcurrencyLimiter}. That limiter exists to
     * bound the expensive parsing work under a burst; here the request does no parsing at all, and
     * queueing an upload behind a permit would reintroduce exactly the waiting this endpoint exists
     * to remove. The bound now lives where the work does — the worker's batch size.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ImportJobDto.Accepted>> submit(
            @RequestParam("file") MultipartFile file) throws Exception {
        // Validated here rather than in the worker: a file the parser will certainly reject should
        // fail while the user is still looking at the upload dialog, not minutes later in a job
        // status they have to go and check.
        //
        // BH-029: decided once, into a local, and then both used to validate and handed to accept()
        // to be persisted. It used to be computed here and computed AGAIN in the worker, so "the
        // format this validates is the format the worker will actually parse with" was a property
        // of two call sites agreeing rather than of anything being recorded.
        StatementUpload.Format format = ImportJobService.formatOf(file.getOriginalFilename());
        StatementUpload.requireReadable(file, format);

        var accepted = ImportJobDto.Accepted.of(
                importJobService.accept(currentUser.id(), file, format));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    /**
     * Whether this deployment takes queued uploads, asked before one is sent.
     *
     * <p>Mapped above {@code /{jobId}} and matched ahead of it because a literal path beats a
     * variable one — but the ordering here is for the reader, not for Spring.
     *
     * <p>The alternative was for the client to try the upload and read the 503, which cannot work:
     * the multipart body is consumed before the handler runs, so the whole file would cross the
     * network before being told to send it somewhere else, and then cross it again. A client that
     * asks first pays one small GET instead.
     */
    @GetMapping("/availability")
    public ApiResponse<ImportJobDto.Availability> availability() {
        return ApiResponse.ok(importJobService.availability());
    }

    /** Progress. Poll at 1-2s; the flow is measured in seconds, so this is cheaper than the
     *  WebSockets or SSE it would otherwise take. */
    @GetMapping("/{jobId}")
    public ApiResponse<ImportJobDto.Progress> progress(@PathVariable UUID jobId) {
        return ApiResponse.ok(importJobService.progress(currentUser.id(), jobId));
    }

    /** The caller's recent imports, for a "your uploads" view and for finding a job whose id the
     *  client lost — a page refresh mid-import should not orphan the work. */
    @GetMapping
    public ApiResponse<List<ImportJobDto.Progress>> recent(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(importJobService.recent(currentUser.id(), limit));
    }

    /**
     * Stops an import the user no longer wants.
     *
     * <p>{@code POST} rather than {@code DELETE}: this ends the work and keeps the row, because a
     * cancelled import is part of the user's history and part of the queue's. {@code DELETE} on this
     * path would reasonably be read as removing the record.
     *
     * <p>Returns the job's new state rather than 204, so the client that just cancelled renders from
     * the response instead of racing its own next poll.
     */
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<ImportJobDto.Progress> cancel(@PathVariable UUID jobId) {
        return ApiResponse.ok(importJobService.cancel(currentUser.id(), jobId), "Import cancelled");
    }
}
