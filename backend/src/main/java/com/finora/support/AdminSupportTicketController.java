package com.finora.support;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.entity.SupportTicket;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The admin ticket queue: list/filter, status transitions, internal notes, and claiming.
 *
 * <p>Gated on {@code SUPPORT_MANAGE} (V149), its own permission rather than a reuse of {@code
 * PLATFORM_DIAGNOSTICS_VIEW} — a ticket's {@code description} is a user's own free-text account of
 * a problem, which routinely quotes their financial detail. Same reasoning V135 applied to the held-
 * imports triage queue. Class-level, so a new endpoint added here is gated by default.
 *
 * <p>There is no ticket-detail endpoint on this controller: an admin reads full detail through
 * {@code SupportTicketController.detail}, the same shared dual-audience route the attachment
 * download uses — see {@code SupportTicketService}'s class doc.
 */
@RestController
@RequestMapping("/api/v1/admin/support/tickets")
@PreAuthorize("hasAuthority('SUPPORT_MANAGE')")
public class AdminSupportTicketController {

    private final SupportTicketService supportTicketService;
    private final CurrentUser currentUser;

    public AdminSupportTicketController(SupportTicketService supportTicketService, CurrentUser currentUser) {
        this.supportTicketService = supportTicketService;
        this.currentUser = currentUser;
    }

    /** The queue, oldest first, optionally filtered by status and/or category and/or a free-text
     *  search over ticket number and subject — see {@code SupportTicketRepository.findForAdmin}'s
     *  own doc comment for exactly what {@code q} searches and why. Carries no customer free text
     *  beyond that — {@code description} is deliberately absent from {@link SupportTicketDto.Summary}. */
    @GetMapping
    public ApiResponse<PagedResponse<SupportTicketDto.Summary>> list(
            @RequestParam(required = false) SupportTicket.Status status,
            @RequestParam(required = false) SupportTicket.Category category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(supportTicketService.adminList(status, category, q, page, size));
    }

    /** 409 on an illegal transition, naming both states — see {@code SupportTicketService.updateStatus}. */
    @PatchMapping("/{id}")
    public ApiResponse<SupportTicketDto.Summary> updateStatus(@PathVariable UUID id,
                                                               @Valid @RequestBody SupportTicketDto.UpdateStatusRequest request) {
        return ApiResponse.ok(supportTicketService.updateStatus(currentUser.id(), id, request.status()), "Status updated");
    }

    @GetMapping("/{id}/notes")
    public ApiResponse<List<SupportTicketDto.NoteDto>> notes(@PathVariable UUID id) {
        return ApiResponse.ok(supportTicketService.listNotes(id));
    }

    @PostMapping("/{id}/notes")
    public ApiResponse<SupportTicketDto.NoteDto> addNote(@PathVariable UUID id,
                                                          @Valid @RequestBody SupportTicketDto.AddNoteRequest request) {
        return ApiResponse.ok(supportTicketService.addNote(currentUser.id(), id, request.note()), "Note added");
    }

    /** Always succeeds, including as a takeover of someone else's claim — see {@code
     *  SupportTicketService.claim}. The takeover itself is silent at the data layer by design; the
     *  admin-portal confirm dialog (Phase 9) is what keeps it from being silent to the admins involved. */
    @PostMapping("/{id}/claim")
    public ApiResponse<SupportTicketDto.Summary> claim(@PathVariable UUID id) {
        return ApiResponse.ok(supportTicketService.claim(currentUser.id(), id), "Claimed");
    }

    /** Any admin may release a claim, not only the one who set it — puts a ticket visibly back in
     *  the unclaimed queue without handing it to a named person. */
    @DeleteMapping("/{id}/claim")
    public ApiResponse<SupportTicketDto.Summary> unclaim(@PathVariable UUID id) {
        return ApiResponse.ok(supportTicketService.unclaim(currentUser.id(), id), "Unclaimed");
    }
}
