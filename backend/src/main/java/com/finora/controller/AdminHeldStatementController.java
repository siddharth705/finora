package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.HeldStatementDetailDto;
import com.finora.dto.HeldStatementDto;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.HeldStatement;
import com.finora.security.CurrentUser;
import com.finora.service.HeldStatementFilter;
import com.finora.service.HeldStatementService;
import com.finora.service.HeldStatementService.DownloadedStatement;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * The trust-review queue's operator surface: the statements the pipeline held back, and the two
 * decisions that end a hold.
 *
 * <p>Gated on {@code TRUST_REVIEW_MANAGE} (V144), its own permission rather than a reuse, for the
 * same reason V135 gave {@code IMPORT_TRIAGE_MANAGE} its own: resolving a hold decides whether a
 * real customer's transactions reach their ledger. {@code PLATFORM_DIAGNOSTICS_VIEW} is explicitly
 * the read-only, no-mutation visibility permission (V34), and approving an import is neither.
 *
 * <p>Class-level, so a new endpoint added here is gated by default.
 */
@RestController
@RequestMapping("/api/v1/admin/held-statements")
@PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")
public class AdminHeldStatementController {

    private final HeldStatementService heldStatementService;
    private final CurrentUser currentUser;

    public AdminHeldStatementController(HeldStatementService heldStatementService,
                                        CurrentUser currentUser) {
        this.heldStatementService = heldStatementService;
        this.currentUser = currentUser;
    }

    /** One page of open holds, oldest first -- the longest-waiting user is the one to look at.
     *  Carries no statement content, which is why browsing is not audited. Every filter is
     *  optional; {@code status} narrows within the open queue and can never surface a resolved
     *  hold -- see {@link HeldStatementFilter}'s own doc. */
    @GetMapping
    public ApiResponse<PagedResponse<HeldStatementDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) HeldStatement.Status status,
            @RequestParam(required = false) String bank,
            @RequestParam(required = false) Integer olderThanHours,
            @RequestParam(required = false) UUID engineerId) {
        return ApiResponse.ok(heldStatementService.list(page, size,
                new HeldStatementFilter(status, bank, olderThanHours, engineerId)));
    }

    /** The evidence behind the trigger, the extraction snapshot, and the audit timeline. Still no
     *  statement content -- opening the document is {@code /document}, gated and audited
     *  separately. */
    @GetMapping("/{heldId}")
    public ApiResponse<HeldStatementDetailDto> detail(@PathVariable String heldId) {
        return ApiResponse.ok(heldStatementService.detail(heldId));
    }

    /**
     * The one endpoint in the product that hands a customer's bank statement to a member of staff.
     *
     * <p>Deliberately pinned to {@code ADMIN} and {@code SUPER_ADMIN} by role, on top of the class's
     * {@code TRUST_REVIEW_MANAGE} permission gate rather than instead of it -- the repository
     * owner's decision, 2026-09-04: that permission is grantable to a future support role who can
     * work the queue, and such a role must still never be able to take the document itself.
     *
     * <p><b>Both conditions are restated in this one expression, not layered as class-level plus
     * method-level.</b> {@code AdminStatementAnalysisController}'s own doc already establishes why
     * that would be wrong here: a method-level {@code @PreAuthorize} REPLACES the class-level rule
     * for Spring Security, it does not add to it. Two separate annotations would have silently
     * dropped the {@code TRUST_REVIEW_MANAGE} check for this one endpoint -- exactly the "widening
     * the permission later widens this too" failure the role pin exists to prevent, just moved to
     * the other gate instead.
     */
    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{heldId}/document")
    public ResponseEntity<byte[]> document(@PathVariable String heldId) {
        DownloadedStatement file = heldStatementService.download(currentUser.id(), heldId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    /** "Assign to Me" is the common case and must not require typing an id: an absent or blank
     *  {@code engineerId} defaults to the calling admin. Reassigning an unresolved hold is allowed;
     *  a resolved one is a 409 naming the state. */
    @PostMapping("/{heldId}/assign")
    public ApiResponse<HeldStatementDto> assign(@PathVariable String heldId,
                                                @RequestBody(required = false) Map<String, String> body) {
        String engineerIdRaw = body == null ? null : body.get("engineerId");
        UUID engineerId = engineerIdRaw == null || engineerIdRaw.isBlank()
                ? null : UUID.fromString(engineerIdRaw);
        return ApiResponse.ok(heldStatementService.assign(currentUser.id(), heldId, engineerId),
                "Assigned");
    }

    @PostMapping("/{heldId}/investigate")
    public ApiResponse<HeldStatementDto> investigate(@PathVariable String heldId) {
        return ApiResponse.ok(heldStatementService.startInvestigation(currentUser.id(), heldId),
                "Investigation started");
    }

    /** Replaces the engineer's write-up wholesale; the history of what it said before lives in the
     *  event this writes, not in a second notes column. */
    @PostMapping("/{heldId}/notes")
    public ApiResponse<HeldStatementDto> notes(@PathVariable String heldId,
                                               @RequestBody(required = false) Map<String, String> body) {
        String notes = body == null ? null : body.get("notes");
        return ApiResponse.ok(heldStatementService.addNotes(currentUser.id(), heldId, notes),
                "Notes saved");
    }

    /** Records what an engineer found and where the fix landed. Replaces both fields wholesale. */
    @PostMapping("/{heldId}/findings")
    public ApiResponse<HeldStatementDto> findings(@PathVariable String heldId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        String rootCause = body == null ? null : body.get("rootCause");
        String fixReference = body == null ? null : body.get("fixReference");
        return ApiResponse.ok(
                heldStatementService.recordFindings(currentUser.id(), heldId, rootCause, fixReference),
                "Findings saved");
    }

    /** Re-parses this hold's original bytes with the parser build running right now and reports
     *  whether it would still be flagged. Writes nothing to the staged rows -- only an event, and
     *  (when it now clears) the status transition to READY_FOR_IMPORT. */
    @PostMapping("/{heldId}/rerun-parser")
    public ApiResponse<HeldStatementRerunResultDto> rerunParser(@PathVariable String heldId) {
        return ApiResponse.ok(heldStatementService.rerunParser(currentUser.id(), heldId));
    }

    /** Releases the hold: the staged rows reach the user's confirm step, and the user is told the
     *  statement is ready -- which is the promise the held-state copy already made them. 409 if
     *  the hold was already resolved, naming the state. */
    @PostMapping("/{heldId}/approve")
    public ApiResponse<HeldStatementDto> approve(@PathVariable String heldId,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        // Boolean.valueOf rather than Boolean.parseBoolean, deliberately -- see Plan 4's own
        // Decisions table: a value going into permanent aggregate metrics should not silently
        // treat a malformed string the same as an explicit "false".
        Boolean falsePositive = body == null || body.get("falsePositive") == null
                ? null : Boolean.valueOf(body.get("falsePositive"));
        return ApiResponse.ok(heldStatementService.approve(currentUser.id(), heldId, note, falsePositive),
                "Import released");
    }

    /** Ends the review the other way: these rows never reach the ledger, and the import lands in
     *  FAILED carrying a reason the user can read. */
    @PostMapping("/{heldId}/reject")
    public ApiResponse<HeldStatementDto> reject(@PathVariable String heldId,
                                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(heldStatementService.reject(currentUser.id(), heldId, reason),
                "Import rejected");
    }
}
