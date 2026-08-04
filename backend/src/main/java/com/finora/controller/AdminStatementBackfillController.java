package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.storage.StatementBackfillService;
import com.finora.imports.storage.StatementBackfillService.BackfillBatchResult;
import com.finora.imports.storage.StatementBackfillService.BackfillStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives the Phase 3 backfill (docs/engineering/statement-storage-migration.md): moving
 * pre-Phase-2 statement bytes out of {@code file_content} and into object storage.
 *
 * <h2>Why an endpoint rather than a scheduled job</h2>
 * This codebase has no background job infrastructure, and for a migration over potentially
 * gigabytes of customer statements that is the better shape anyway: an operator runs a batch,
 * reads the result, and decides whether to run another. A scheduler would make the same migration
 * unobservable and awkward to stop.
 *
 * <h2>Gating</h2>
 * PLATFORM_DIAGNOSTICS_VIEW, matching AdminSystemController, which is the closest existing
 * precedent for a system-maintenance operation. Worth noting the asymmetry deliberately: unlike the
 * other endpoints under that authority this one MUTATES. It is nonetheless safe by construction --
 * it only ever fills in two previously-null columns and writes objects that are addressed by their
 * own content, never deletes, never overwrites, and never touches a row that already has an
 * address. Re-running it is the intended usage, not a hazard.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/storage")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminStatementBackfillController {

    private final StatementBackfillService backfillService;

    public AdminStatementBackfillController(StatementBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /**
     * How much is left, and whether a run is even possible.
     *
     * {@code complete} reaching true is the precondition for Phase 4 — dropping
     * {@code file_content} before then would destroy the only copy of every unaddressed row.
     */
    @GetMapping("/backfill")
    public ApiResponse<BackfillStatus> status() {
        return ApiResponse.ok(backfillService.status());
    }

    /**
     * Addresses one batch. Call repeatedly until {@code remaining} is zero.
     *
     * Deliberately not a single "migrate everything" call: batches keep memory bounded (each row
     * can carry 10MB), keep the operation interruptible, and give an operator a decision point
     * between each one. `stored` versus `deduplicated` in the response is also the first real
     * measurement of how much of the database was the same file stored repeatedly.
     */
    @PostMapping("/backfill")
    public ApiResponse<BackfillBatchResult> runBatch(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(backfillService.runBatch(limit), "Backfill batch complete");
    }
}
