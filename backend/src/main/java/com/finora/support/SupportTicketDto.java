package com.finora.support;

import com.finora.entity.ClientPlatform;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.repository.SupportTicketAttachmentRepository.AttachmentMetadata;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The support-ticket API contract — one file, several nested records, following {@code ImportDto}
 *  and {@code StatementImportDto}'s own convention for a feature with more than one shape. */
public final class SupportTicketDto {

    private SupportTicketDto() {}

    /** List-row shape: everything a "My Tickets" row or an admin queue row needs, and nothing that
     *  requires a second query to produce. Deliberately excludes {@code description} — the same
     *  reason {@code HeldImportDto}'s list shape excludes the raw parser error. */
    public record Summary(
            UUID id,
            String ticketNumber,
            UUID userId,
            SupportTicket.Category category,
            String subject,
            SupportTicket.Status status,
            UUID claimedByAdminId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static Summary from(SupportTicket ticket) {
            return new Summary(ticket.getId(), ticket.getTicketNumber(), ticket.getUserId(),
                    ticket.getCategory(), ticket.getSubject(), ticket.getStatus(),
                    ticket.getClaimedByAdminId(), ticket.getCreatedAt(), ticket.getUpdatedAt());
        }
    }

    /** Single-ticket shape, including the attachment list. Served by one route to two audiences —
     *  see {@code SupportTicketService.getDetail}'s own doc for why there is no separate admin
     *  detail endpoint. */
    public record Detail(
            UUID id,
            String ticketNumber,
            UUID userId,
            SupportTicket.Category category,
            String subject,
            String description,
            SupportTicket.Status status,
            ClientPlatform source,
            String appVersion,
            UUID claimedByAdminId,
            Instant resolvedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt,
            List<AttachmentSummary> attachments
    ) {
        public static Detail from(SupportTicket ticket, List<AttachmentSummary> attachments) {
            return new Detail(ticket.getId(), ticket.getTicketNumber(), ticket.getUserId(),
                    ticket.getCategory(), ticket.getSubject(), ticket.getDescription(), ticket.getStatus(),
                    ticket.getSource(), ticket.getAppVersion(), ticket.getClaimedByAdminId(),
                    ticket.getResolvedAt(), ticket.getClosedAt(), ticket.getCreatedAt(), ticket.getUpdatedAt(),
                    attachments);
        }
    }

    /** Metadata only — never the bytes. Mirrors {@code AttachmentMetadata}, the projection that
     *  keeps a ticket-detail read from pulling every attachment's content into memory. */
    public record AttachmentSummary(UUID id, String filename, String contentType, long sizeBytes) {
        public static AttachmentSummary from(AttachmentMetadata metadata) {
            return new AttachmentSummary(metadata.getId(), metadata.getFilename(),
                    metadata.getContentType(), metadata.getSizeBytes());
        }
    }

    public record UpdateStatusRequest(@NotNull(message = "status is required") SupportTicket.Status status) {}

    /** An internal note, as returned to an admin. Never constructed from, or exposed to, a
     *  user-facing endpoint — see {@link SupportTicketInternalNote}'s own doc for why that must
     *  stay structurally true rather than merely conventionally true. */
    public record NoteDto(UUID id, UUID adminId, String note, Instant createdAt) {
        public static NoteDto from(SupportTicketInternalNote note) {
            return new NoteDto(note.getId(), note.getAdminId(), note.getNote(), note.getCreatedAt());
        }
    }

    public record AddNoteRequest(@NotBlank(message = "note is required") String note) {}
}
