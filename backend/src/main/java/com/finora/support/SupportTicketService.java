package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.exception.ApiException;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketInternalNoteRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.service.AuditService;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>Audit: owner as subject, actor in metadata</h2>
 *
 * <p>Six events, all string literals passed to {@code AuditService.record} — there is no {@code
 * AuditAction} catalog to extend, matching that service's own documented convention.
 * {@code SUPPORT_TICKET_CREATED} is self-service (the ticket's own {@code userId} is the only
 * actor, and this method is never admin-reachable). The other five are admin actions on a user's
 * own ticket, and follow {@code AccountService.create}'s convention exactly: {@code record}'s first
 * argument stays the ticket owner's {@code userId} (the subject whose data changed), and the acting
 * admin goes into {@code metadata["actorId"]} — never the reverse. Every admin-only-reachable
 * method that writes one of these carries a parameter literally named {@code actingAdminId}, not
 * merely a differently-named admin id: {@code AuditActorAttributionTest} walks the call graph from
 * every {@code /api/v1/admin/**} controller and fails the build on an audited write it can reach
 * without that exact parameter name — the same rule that already caught eight real instances of
 * this bug elsewhere in the codebase (see that test's own doc). {@link #getDetail} and {@link
 * #downloadAttachment} are the exception to the parameter name, not to the underlying rule: both are
 * reachable by an admin AND the ticket's own owner through the single shared route ({@code callerId}
 * already carries whichever one actually called), and their controller is
 * {@code SupportTicketController} under {@code /api/v1/support}, not an admin-rooted path — so
 * {@code AuditActorAttributionTest}'s walk, which starts only from {@code /api/v1/admin/**}
 * controllers, never reaches either at all. {@code SUPPORT_TICKET_VIEWED} and {@code
 * SUPPORT_TICKET_ATTACHMENT_DOWNLOADED} fire only when {@code callerIsAdmin} is true, following the
 * same reasoning {@code AdminHeldImportController.detail} states for auditing every open of a detail
 * view that can surface a user's own free text — a downloaded attachment is raw file content and
 * gets the identical posture.
 *
 * <p>{@code SUPPORT_TICKET_CLAIMED} covers claim, unclaim and a takeover as one event type,
 * carrying {@code previousAdminId} and {@code newAdminId} — both keys always present, including
 * when either is null, so all three shapes reconstruct from one action string. That is also why
 * {@link #recordClaimChange} builds its metadata with a mutable map rather than {@code Map.of(...)}:
 * {@code Map.of} throws on a null value, and a first claim or an unclaim is exactly a null value.
 *
 * <p>No {@code recordEvenOnRollback} anywhere here, on purpose. That variant exists for a write
 * recorded immediately before a later throw in the same transaction rolls it back with everything
 * else (see that method's own doc — a real bug once in {@code DataExportService}). Every audit call
 * below runs only after its corresponding {@code save} has already succeeded, with nothing riskier
 * after it in the same method, so the plain, transaction-joining {@code record} is the correct
 * choice: if the transaction does roll back for some other reason, the audit entry should too,
 * because then the action it describes did not actually happen.
 */
@Service
public class SupportTicketService {

    private static final int MAX_SUBJECT_LENGTH = 120;

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketAttachmentRepository attachmentRepository;
    private final SupportTicketInternalNoteRepository noteRepository;
    private final SupportTicketIdGenerator idGenerator;
    private final ClientIdentity clientIdentity;
    private final AuditService auditService;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                 SupportTicketAttachmentRepository attachmentRepository,
                                 SupportTicketInternalNoteRepository noteRepository,
                                 SupportTicketIdGenerator idGenerator,
                                 ClientIdentity clientIdentity,
                                 AuditService auditService) {
        this.ticketRepository = ticketRepository;
        this.attachmentRepository = attachmentRepository;
        this.noteRepository = noteRepository;
        this.idGenerator = idGenerator;
        this.clientIdentity = clientIdentity;
        this.auditService = auditService;
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

        auditService.record(userId, "SUPPORT_TICKET_CREATED", "SupportTicket", saved.getId(),
                Map.of("ticketNumber", saved.getTicketNumber(), "category", category.name()));
        return SupportTicketDto.Detail.from(saved, attachments);
    }

    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketDto.Summary> listOwn(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        return PagedResponse.of(
                ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(SupportTicketDto.Summary::from));
    }

    /**
     * Not {@code readOnly = true}, unlike {@link #listOwn}, {@link #adminList} and {@link
     * #listNotes} — {@code AdminHeldImportService.detail} is the precedent: it drops the hint for
     * exactly the same reason, because a read that also writes an audit row is not read-only, and
     * {@code readOnly} is a hint the JDBC driver can act on (it maps to {@code
     * Connection.setReadOnly(true)} in this app's Postgres configuration), not decoration — an
     * INSERT inside a connection actually marked read-only fails at the database, it does not
     * silently no-op. {@link #downloadAttachment} carries the identical exception and reasoning.
     */
    @Transactional
    public SupportTicketDto.Detail getDetail(UUID callerId, boolean callerIsAdmin, UUID ticketId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        List<SupportTicketDto.AttachmentSummary> attachments = attachmentRepository.findMetadataByTicketId(ticket.getId())
                .stream().map(SupportTicketDto.AttachmentSummary::from).toList();
        if (callerIsAdmin) {
            auditService.record(ticket.getUserId(), "SUPPORT_TICKET_VIEWED", "SupportTicket", ticket.getId(),
                    Map.of("actorId", callerId.toString(), "ticketNumber", ticket.getTicketNumber()));
        }
        return SupportTicketDto.Detail.from(ticket, attachments);
    }

    /** Not {@code readOnly = true} — see {@link #getDetail}'s own doc for why. A downloaded
     *  attachment is raw file content (a screenshot can show as much financial detail as free text),
     *  so an admin download gets the identical audit posture as opening the ticket itself. */
    @Transactional
    public AttachmentDownload downloadAttachment(UUID callerId, boolean callerIsAdmin, UUID ticketId, UUID attachmentId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        SupportTicketAttachment attachment = attachmentRepository.findByIdAndTicketId(attachmentId, ticket.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (callerIsAdmin) {
            auditService.record(ticket.getUserId(), "SUPPORT_TICKET_ATTACHMENT_DOWNLOADED", "SupportTicket", ticket.getId(),
                    Map.of("actorId", callerId.toString(), "ticketNumber", ticket.getTicketNumber(),
                            "attachmentId", attachment.getId().toString(), "filename", attachment.getFilename()));
        }
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
    public SupportTicketDto.Summary updateStatus(UUID actingAdminId, UUID ticketId, SupportTicket.Status newStatus) {
        SupportTicket ticket = requireTicket(ticketId);
        SupportTicket.Status previousStatus = ticket.getStatus();
        if (!previousStatus.canTransitionTo(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot move a ticket from " + previousStatus + " to " + newStatus + ".");
        }
        ticket.setStatus(newStatus);
        Instant now = Instant.now();
        if (newStatus == SupportTicket.Status.RESOLVED) {
            ticket.setResolvedAt(now);
        } else if (newStatus == SupportTicket.Status.CLOSED) {
            ticket.setClosedAt(now);
        }
        SupportTicket saved = ticketRepository.save(ticket);
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_STATUS_CHANGED", "SupportTicket", ticket.getId(),
                Map.of("actorId", actingAdminId.toString(), "ticketNumber", ticket.getTicketNumber(),
                        "previousStatus", previousStatus.name(), "newStatus", newStatus.name()));
        return SupportTicketDto.Summary.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketDto.NoteDto> listNotes(UUID ticketId) {
        requireTicket(ticketId);
        return noteRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(SupportTicketDto.NoteDto::from).toList();
    }

    /** The note body is deliberately not copied into the audit metadata — the note table is already
     *  append-only and admin-scoped, so duplicating free text into a second store widens the surface
     *  for no gain (V147's own reasoning for the note table itself). */
    @Transactional
    public SupportTicketDto.NoteDto addNote(UUID actingAdminId, UUID ticketId, String note) {
        SupportTicket ticket = requireTicket(ticketId);
        SupportTicketInternalNote entity = new SupportTicketInternalNote();
        entity.setTicketId(ticketId);
        entity.setAdminId(actingAdminId);
        entity.setNote(requireNonBlank(note, "Note"));
        SupportTicketInternalNote saved = noteRepository.save(entity);
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_NOTE_ADDED", "SupportTicket", ticketId,
                Map.of("actorId", actingAdminId.toString(), "ticketNumber", ticket.getTicketNumber()));
        return SupportTicketDto.NoteDto.from(saved);
    }

    /** Always succeeds, including on an already-claimed ticket — see {@link SupportTicket#getClaimedByAdminId()}'s
     *  own doc: a claim warns, it never blocks, so one admin's absence cannot freeze a customer's ticket. */
    @Transactional
    public SupportTicketDto.Summary claim(UUID actingAdminId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        UUID previousAdminId = ticket.getClaimedByAdminId();
        ticket.setClaimedByAdminId(actingAdminId);
        SupportTicket saved = ticketRepository.save(ticket);
        recordClaimChange(actingAdminId, ticket, previousAdminId, actingAdminId);
        return SupportTicketDto.Summary.from(saved);
    }

    /** Releases a claim back to unclaimed. Any admin may call this, not only the one who claimed
     *  it — so a ticket nobody is actively working can visibly go back to the queue. */
    @Transactional
    public SupportTicketDto.Summary unclaim(UUID actingAdminId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        UUID previousAdminId = ticket.getClaimedByAdminId();
        ticket.setClaimedByAdminId(null);
        SupportTicket saved = ticketRepository.save(ticket);
        recordClaimChange(actingAdminId, ticket, previousAdminId, null);
        return SupportTicketDto.Summary.from(saved);
    }

    /** The single write point for {@code SUPPORT_TICKET_CLAIMED} — claim, unclaim and a takeover
     *  all pass through here so the three shapes stay reconstructable from one action string.
     *  {@code actingAdminId} is who performed THIS action, which is not always {@code newAdminId}:
     *  on an unclaim the actor releases the ticket, they don't become its new claimant. */
    private void recordClaimChange(UUID actingAdminId, SupportTicket ticket, UUID previousAdminId, UUID newAdminId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("actorId", actingAdminId.toString());
        metadata.put("ticketNumber", ticket.getTicketNumber());
        metadata.put("previousAdminId", previousAdminId == null ? null : previousAdminId.toString());
        metadata.put("newAdminId", newAdminId == null ? null : newAdminId.toString());
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_CLAIMED", "SupportTicket", ticket.getId(), metadata);
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
