package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.HeldStatementDetailDto;
import com.finora.dto.HeldStatementDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.HeldStatement;
import com.finora.security.CurrentUser;
import com.finora.service.HeldStatementFilter;
import com.finora.service.HeldStatementService;
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

    /** Releases the hold: the staged rows reach the user's confirm step, and the user is told the
     *  statement is ready -- which is the promise the held-state copy already made them. 409 if
     *  the hold was already resolved, naming the state. */
    @PostMapping("/{heldId}/approve")
    public ApiResponse<HeldStatementDto> approve(@PathVariable String heldId,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ApiResponse.ok(heldStatementService.approve(currentUser.id(), heldId, note),
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
