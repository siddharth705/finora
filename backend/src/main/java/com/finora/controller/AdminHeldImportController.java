package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.HeldImportDto;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import com.finora.service.AdminHeldImportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The held-imports triage queue's operator surface.
 *
 * <p>Gated on {@code IMPORT_TRIAGE_MANAGE} (V135), its own permission rather than a reuse. Every
 * action here touches a real customer's bank statement -- the detail endpoint returns the raw
 * parser error, which routinely quotes the document that defeated it. {@code
 * PLATFORM_DIAGNOSTICS_VIEW} is explicitly the read-only, no-mutation visibility permission (V34)
 * and reprocessing re-runs someone's import; {@code LEARNING_QUEUE_MANAGE} is the wrong shape too,
 * since clearing a merchant-learning backlog should not come with the ability to read statements.
 * Same reasoning V63 applied when it gave the learning queue its own permission.
 *
 * <p>Class-level, so a new endpoint added here is gated by default.
 */
@RestController
@RequestMapping("/api/v1/admin/held-imports")
@PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE')")
public class AdminHeldImportController {

    private final AdminHeldImportService heldImportService;
    private final CurrentUser currentUser;

    public AdminHeldImportController(AdminHeldImportService heldImportService, CurrentUser currentUser) {
        this.heldImportService = heldImportService;
        this.currentUser = currentUser;
    }

    /** One page of held imports, oldest first. Carries no customer content -- see {@link
     *  HeldImportDto} -- which is why browsing is not audited and opening one is. */
    @GetMapping
    public ApiResponse<PagedResponse<HeldImportDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(heldImportService.list(page, size));
    }

    /** Counts for the header and the sidebar badge. */
    @GetMapping("/summary")
    public ApiResponse<HeldImportDto.Summary> summary() {
        return ApiResponse.ok(heldImportService.summary());
    }

    /** One job's diagnostics, including the raw parser error. Audited on every call. */
    @GetMapping("/{jobId}")
    public ApiResponse<HeldImportDto.Detail> detail(@PathVariable UUID jobId) {
        return ApiResponse.ok(heldImportService.detail(currentUser.id(), jobId));
    }

    /** Requeues one held job with a fresh attempt budget. 409 if it is in any other state, naming
     *  that state, so an operator can tell "someone already reprocessed this" from "this cannot
     *  be". */
    @PostMapping("/{jobId}/reprocess")
    public ApiResponse<HeldImportDto> reprocess(@PathVariable UUID jobId) {
        return ApiResponse.ok(heldImportService.reprocess(currentUser.id(), jobId),
                "Queued for reprocessing");
    }

    /** Requeues every held job, bounded. Returns the count so the response can say how many rather
     *  than just "done" -- a capped batch that reports success reads as a complete one. */
    @PostMapping("/reprocess-all")
    public ApiResponse<Map<String, Integer>> reprocessAll() {
        int reprocessed = heldImportService.reprocessAll(currentUser.id());
        return ApiResponse.ok(Map.of("reprocessed", reprocessed),
                reprocessed + " queued for reprocessing");
    }

    /** Gives up on a held job, landing it in the plain FAILED it would have reached without this
     *  feature. The reason is recorded on the audit entry, not on the job. */
    @PostMapping("/{jobId}/resolve")
    public ApiResponse<HeldImportDto> resolve(@PathVariable UUID jobId,
                                              @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(heldImportService.resolve(currentUser.id(), jobId, reason), "Resolved");
    }
}
