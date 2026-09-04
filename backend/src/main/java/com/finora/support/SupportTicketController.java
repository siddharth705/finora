package com.finora.support;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * The user-facing ticket surface: create, "My Tickets", one ticket's detail, and its attachment.
 *
 * <p>{@code detail} and {@code downloadAttachment} are also reachable by an admin holding {@code
 * SUPPORT_MANAGE} — see {@link SupportTicketService}'s class doc for why that is one route with an
 * internal check rather than a duplicate admin route. {@code isAdminCaller()} resolves the same way
 * on both.
 *
 * <p>{@code category} arrives here as a raw multipart string and is parsed inside {@link
 * SupportTicketService#create}, not in this class — {@code LayerDependencyDirectionTest
 * .controllersNeverReturnAnEntity} rejects a controller method (private helpers included) whose
 * return type touches {@code com.finora.entity}, and CODING_STANDARDS.md's "controllers stay thin"
 * rule points the same parsing at the service either way.
 */
@RestController
@RequestMapping("/api/v1/support/tickets")
public class SupportTicketController {

    /** Matches V149's permission name exactly — see {@code AdminSupportTicketController}, which
     *  gates its whole surface on the identical string via {@code @PreAuthorize}. */
    private static final String SUPPORT_MANAGE = "SUPPORT_MANAGE";

    private final SupportTicketService supportTicketService;
    private final CurrentUser currentUser;

    public SupportTicketController(SupportTicketService supportTicketService, CurrentUser currentUser) {
        this.supportTicketService = supportTicketService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<SupportTicketDto.Detail> create(
            @RequestParam("category") String category,
            @RequestParam("subject") String subject,
            @RequestParam("description") String description,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok(
                supportTicketService.create(currentUser.id(), category, subject, description, file),
                "Support ticket created");
    }

    @GetMapping
    public ApiResponse<PagedResponse<SupportTicketDto.Summary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(supportTicketService.listOwn(currentUser.id(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupportTicketDto.Detail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(supportTicketService.getDetail(currentUser.id(), isAdminCaller(), id));
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        var download = supportTicketService.downloadAttachment(currentUser.id(), isAdminCaller(), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .body(download.content());
    }

    private boolean isAdminCaller() {
        return currentUser.hasAuthority(SUPPORT_MANAGE);
    }
}
