package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.exception.ApiException;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketInternalNoteRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestration for support tickets: creation, the user's own view, the admin queue, status
 * transitions, claiming, and internal notes.
 *
 * <h2>One route serves two audiences, twice</h2>
 *
 * <p>{@link #getDetail} and {@link #downloadAttachment} are each reachable by a ticket's owner OR
 * by any admin holding {@code SUPPORT_MANAGE} — not by a separate admin-only route. The proposal's
 * §3.6 says exactly this for attachments: "every download goes through an authenticated endpoint
 * ... that re-checks ownership/admin status per request." The revised plan's own endpoint count (11
 * total, five admin-prefixed) has no separate "admin ticket detail" entry, and the security section
 * describes viewing-your-own and admin-viewing-all as two checks on one read path, not two routes —
 * the same shape the attachment sentence states explicitly. Both methods therefore take a
 * {@code callerIsAdmin} flag resolved by the controller from {@code CurrentUser.hasAuthority
 * ("SUPPORT_MANAGE")}, and {@link #fetchForCaller} is the one place that branches on it.
 *
 * <h2>Ownership stays a predicate on the fetch</h2>
 *
 * <p>For the non-admin path, {@link #fetchForCaller} calls {@code
 * SupportTicketRepository.findByIdAndUserId} — the same shape {@code StatementImportService.getFile}
 * uses — rather than {@code findById} plus an {@code if}. Only the admin branch uses a bare
 * {@code findById}, and only because there is no single owner to scope it to.
 *
 * <h2>No audit calls here, on purpose</h2>
 *
 * <p>Audit integration is Phase 5, not this phase. {@link #updateStatus} centralises every status
 * transition in one method for exactly the reason the plan names — that is where an audit call (and
 * later a notification call) attaches — but does not make one yet. Wiring {@code AuditService} in
 * here without also deciding {@code SUPPORT_TICKET_STATUS_CHANGED}'s actor/owner convention (the
 * codebase is inconsistent between the two — see the plan's Phase 5 notes) is exactly the kind of
 * decision this phase should not make by accident.
 */
@Service
public class SupportTicketService {

    private static final int MAX_SUBJECT_LENGTH = 120;

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketAttachmentRepository attachmentRepository;
    private final SupportTicketInternalNoteRepository noteRepository;
    private final SupportTicketIdGenerator idGenerator;
    private final ClientIdentity clientIdentity;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                 SupportTicketAttachmentRepository attachmentRepository,
                                 SupportTicketInternalNoteRepository noteRepository,
                                 SupportTicketIdGenerator idGenerator,
                                 ClientIdentity clientIdentity) {
        this.ticketRepository = ticketRepository;
        this.attachmentRepository = attachmentRepository;
        this.noteRepository = noteRepository;
        this.idGenerator = idGenerator;
        this.clientIdentity = clientIdentity;
    }

    /**
     * Validates the file, if any, BEFORE writing the ticket — a rejected attachment (too large, an
     * unrecognised format) must never leave behind a ticket with no attachment the user thought
     * they were sending, which a validate-after-save order would risk even inside one transaction.
     */
    @Transactional
    public SupportTicketDto.Detail create(UUID userId, String rawCategory, String subject,
                                          String description, MultipartFile file) {
        SupportTicket.Category category = parseCategory(rawCategory);
        String trimmedSubject = requireBounded(subject, "Subject", MAX_SUBJECT_LENGTH);
        String trimmedDescription = requireNonBlank(description, "Description");
        SupportAttachmentUpload.Validated validated =
                (file != null && !file.isEmpty()) ? SupportAttachmentUpload.validate(file) : null;

        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNumber(idGenerator.next());
        ticket.setUserId(userId);
        ticket.setCategory(category);
        ticket.setSubject(trimmedSubject);
        ticket.setDescription(trimmedDescription);
        ticket.setSource(clientIdentity.platform());
        ticket.setAppVersion(clientIdentity.appVersion());
        SupportTicket saved = ticketRepository.save(ticket);

        List<SupportTicketDto.AttachmentSummary> attachments = List.of();
        if (validated != null) {
            SupportTicketAttachment attachment = new SupportTicketAttachment();
            attachment.setTicketId(saved.getId());
            attachment.setFilename(validated.filename());
            attachment.setContentType(validated.format().contentType());
            attachment.setSizeBytes(validated.content().length);
            attachment.setSha256Hash(validated.sha256Hash());
            attachment.setContent(validated.content());
            SupportTicketAttachment savedAttachment = attachmentRepository.save(attachment);
            attachments = List.of(new SupportTicketDto.AttachmentSummary(savedAttachment.getId(),
                    savedAttachment.getFilename(), savedAttachment.getContentType(), savedAttachment.getSizeBytes()));
        }

        return SupportTicketDto.Detail.from(saved, attachments);
    }

    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketDto.Summary> listOwn(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        return PagedResponse.of(
                ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(SupportTicketDto.Summary::from));
    }

    @Transactional(readOnly = true)
    public SupportTicketDto.Detail getDetail(UUID callerId, boolean callerIsAdmin, UUID ticketId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        List<SupportTicketDto.AttachmentSummary> attachments = attachmentRepository.findMetadataByTicketId(ticket.getId())
                .stream().map(SupportTicketDto.AttachmentSummary::from).toList();
        return SupportTicketDto.Detail.from(ticket, attachments);
    }

    @Transactional(readOnly = true)
    public AttachmentDownload downloadAttachment(UUID callerId, boolean callerIsAdmin, UUID ticketId, UUID attachmentId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        SupportTicketAttachment attachment = attachmentRepository.findByIdAndTicketId(attachmentId, ticket.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
        return new AttachmentDownload(attachment.getFilename(), attachment.getContentType(), attachment.getContent());
    }

    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketDto.Summary> adminList(SupportTicket.Status status, SupportTicket.Category category,
                                                              int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        return PagedResponse.of(
                ticketRepository.findForAdmin(status, category, pageable).map(SupportTicketDto.Summary::from));
    }

    /** The single place a ticket's status changes. 409, naming both states, on any move {@link
     *  SupportTicket.Status#canTransitionTo} rejects — mirrors {@code AdminHeldImportController}'s
     *  own stated convention: an operator can tell "already moved" from "not a legal move". */
    @Transactional
    public SupportTicketDto.Summary updateStatus(UUID ticketId, SupportTicket.Status newStatus) {
        SupportTicket ticket = requireTicket(ticketId);
        if (!ticket.getStatus().canTransitionTo(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot move a ticket from " + ticket.getStatus() + " to " + newStatus + ".");
        }
        ticket.setStatus(newStatus);
        Instant now = Instant.now();
        if (newStatus == SupportTicket.Status.RESOLVED) {
            ticket.setResolvedAt(now);
        } else if (newStatus == SupportTicket.Status.CLOSED) {
            ticket.setClosedAt(now);
        }
        return SupportTicketDto.Summary.from(ticketRepository.save(ticket));
    }

    @Transactional(readOnly = true)
    public List<SupportTicketDto.NoteDto> listNotes(UUID ticketId) {
        requireTicket(ticketId);
        return noteRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(SupportTicketDto.NoteDto::from).toList();
    }

    @Transactional
    public SupportTicketDto.NoteDto addNote(UUID adminId, UUID ticketId, String note) {
        requireTicket(ticketId);
        SupportTicketInternalNote entity = new SupportTicketInternalNote();
        entity.setTicketId(ticketId);
        entity.setAdminId(adminId);
        entity.setNote(requireNonBlank(note, "Note"));
        return SupportTicketDto.NoteDto.from(noteRepository.save(entity));
    }

    /** Always succeeds, including on an already-claimed ticket — see {@link SupportTicket#getClaimedByAdminId()}'s
     *  own doc: a claim warns, it never blocks, so one admin's absence cannot freeze a customer's ticket. */
    @Transactional
    public SupportTicketDto.Summary claim(UUID adminId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        ticket.setClaimedByAdminId(adminId);
        return SupportTicketDto.Summary.from(ticketRepository.save(ticket));
    }

    /** Releases a claim back to unclaimed. Any admin may call this, not only the one who claimed
     *  it — so a ticket nobody is actively working can visibly go back to the queue. */
    @Transactional
    public SupportTicketDto.Summary unclaim(UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        ticket.setClaimedByAdminId(null);
        return SupportTicketDto.Summary.from(ticketRepository.save(ticket));
    }

    /** Owner-scoped for a regular caller, unscoped for an admin — the one branch point both
     *  dual-audience reads share. */
    private SupportTicket fetchForCaller(UUID callerId, boolean callerIsAdmin, UUID ticketId) {
        if (callerIsAdmin) {
            return ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
        }
        return ticketRepository.findByIdAndUserId(ticketId, callerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
    }

    private SupportTicket requireTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " is required.");
        }
        return value.trim();
    }

    private static String requireBounded(String value, String label, int maxLength) {
        String trimmed = requireNonBlank(value, label);
        if (trimmed.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " must be " + maxLength + " characters or fewer.");
        }
        return trimmed;
    }

    /** {@code category} arrives from a multipart form field, so unlike the JSON endpoints there is
     *  no Jackson enum conversion to lean on — this is that conversion's manual equivalent. Lives
     *  here rather than on the controller: {@code LayerDependencyDirectionTest
     *  .controllersNeverReturnAnEntity} rejects any {@code *Controller} method whose return type
     *  touches {@code com.finora.entity}, private helpers included. */
    private static SupportTicket.Category parseCategory(String raw) {
        try {
            return SupportTicket.Category.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown category: " + raw);
        }
    }

    public record AttachmentDownload(String filename, String contentType, byte[] content) {}
}
