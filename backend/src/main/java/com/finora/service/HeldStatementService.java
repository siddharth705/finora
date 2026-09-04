package com.finora.service;

import com.finora.dto.HeldStatementDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportSessionService;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A held statement's whole life: opened by the worker when the trust predicate fires, and ended by
 * an operator deciding whether the extraction may reach the user's ledger.
 *
 * <p>{@code createHold} runs {@code REQUIRES_NEW} so the hold's own row and its first audit event
 * commit together and independently of the job's transaction. The worker treats a failure there as
 * non-fatal and holds the import anyway, so a half-written hold -- a row with no event, or an event
 * with no row -- would be worse than none: the operator queue would show a review with no history
 * explaining it.
 */
@Service
public class HeldStatementService {

    /** The working queue: everything an operator can still act on. */
    private static final List<HeldStatement.Status> OPEN = List.of(
            HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
            HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT);

    private final HeldStatementRepository repository;
    private final HeldStatementEventRepository eventRepository;
    private final HeldStatementIdGenerator idGenerator;
    private final ImportJobRepository importJobRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ImportSessionService importSessionService;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator,
                                ImportJobRepository importJobRepository,
                                AuditService auditService,
                                NotificationService notificationService,
                                ImportSessionService importSessionService) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.importJobRepository = importJobRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.importSessionService = importSessionService;
    }

    /**
     * Opens the review for one held import, or returns the review that already exists.
     *
     * <p>Idempotent on {@code import_job_id}, which is UNIQUE in V144, and that matters because
     * this commits in its own transaction: if the job's own transition then fails, the pass
     * retries and arrives here a second time for the same job. Inserting blindly would hit the
     * unique constraint, the worker would log it as a failed hold, and the import would end up
     * held with no review record -- despite a perfectly good one already sitting in the table.
     * Returning the existing hold keeps the retry harmless, and deliberately does NOT mint a
     * second Held ID: the operator is looking at one statement, not two.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HeldStatement createHold(ImportJob job, StagedForJob staged, HoldDecision decision,
                                    String parserVersion) {
        return repository.findByImportJobId(job.getId())
                .orElseGet(() -> openHold(job, staged, decision, parserVersion));
    }

    private HeldStatement openHold(ImportJob job, StagedForJob staged, HoldDecision decision,
                                   String parserVersion) {
        VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());

        HeldStatement held = new HeldStatement(idGenerator.next(), job.getId(), job.getUserId(),
                job.getObjectKey(), decision.summary());
        // Snapshotted, not read live: a re-run under a later build has to be comparable against
        // what the build that produced this actually saw. isEmpty() distinguishes "nothing was
        // verified" from "verification found nothing", which are different facts about a hold.
        held.recordSnapshot(parserVersion,
                telemetry.reliabilityStatus() == null ? null : telemetry.reliabilityStatus().name(),
                telemetry.textSource(),
                telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain());
        // staged.bankName() is already carried on StagedForJob for the completion notification --
        // see that record's own doc for why ImportJob can never learn the bank live.
        held.recordBank(staged.bankName());
        repository.save(held);

        // actorId null: the system opened this, not a person. The reasons are recorded here as
        // well as on the row because the row's summary is editable context for an operator, while
        // the event is the immutable record of what the predicate actually said at hold time.
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        return held;
    }

    // --- operator resolution ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<HeldStatementDto> list(int page, int size) {
        Page<HeldStatement> result = repository.findByStatusIn(OPEN,
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
        return PagedResponse.of(result.map(HeldStatementDto::from));
    }

    @Transactional(readOnly = true)
    public HeldStatementDto detail(String heldId) {
        return HeldStatementDto.from(require(heldId));
    }

    /**
     * Releases the hold: the staged rows may now reach the user's confirm step.
     *
     * <p>The notification is not optional politeness. The held-state copy the user is shown says,
     * in as many words, "we'll notify you once it's ready" -- and the worker's own
     * {@code notifyIfPreviouslyHeld} cannot cover this, because it gates on {@code
     * wasHeldForReview}, which a trust hold deliberately never sets. Without this the promise would
     * simply not be kept: the import would quietly become available and nobody would be told.
     *
     * <p>The bank name is not available here. {@code ImportJob} never learns it -- see
     * {@code StagedForJob}'s own doc -- so this uses the template's documented fallback, giving
     * "Your bank statement is ready". Loading the session to recover the name would be a database
     * round trip to improve one word.
     */
    @Transactional
    public HeldStatementDto approve(UUID actingAdminId, String heldId, String note) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "approved");

        ImportJob job = requireJob(held);
        Instant now = Instant.now();
        HeldStatement.Status from = held.getStatus();

        held.markImported(actingAdminId, now);
        job.releaseAfterTrustReview(now);
        repository.save(held);
        importJobRepository.save(job);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "APPROVED",
                from.name(), held.getStatus().name(), note));
        auditService.record(actingAdminId, "TRUST_REVIEW_APPROVED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        // Map.of rejects nulls, and an operator is not required to explain
                        // themselves -- the empty string keeps the entry writable either way.
                        "note", note == null ? "" : note));

        // Before the notification, deliberately. The sweep's exemption lifts the moment this job
        // leaves HELD_FOR_TRUST_REVIEW, and the session's expiresAt is still whatever staging set
        // it to -- long elapsed for any review worth holding for. Telling the user their statement
        // is ready and letting the next sweep delete it minutes later is the same broken promise,
        // moved. They have not seen these rows yet; the wait was ours.
        importSessionService.renewExpiry(job.getImportSessionId());

        notifyStatementReady(job);
        return HeldStatementDto.from(held);
    }

    /**
     * Ends the review the other way: these rows never reach the ledger.
     *
     * <p>Deliberately no notification, which matches every other import failure in this system --
     * a success after a hold announces itself, a failure does not. The user's progress screen shows
     * the failure and the reason carried by {@code IMPORT_TRUST_REVIEW_REJECTED}.
     *
     * <p>The operator's reason goes on the audit entry and the event, never onto the row's
     * {@code engineerNotes}: there is one notes column, and overwriting it here would destroy the
     * investigation findings the rejection was based on.
     */
    @Transactional
    public HeldStatementDto reject(UUID actingAdminId, String heldId, String reason) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "rejected");

        ImportJob job = requireJob(held);
        Instant now = Instant.now();
        HeldStatement.Status from = held.getStatus();

        held.reject(actingAdminId, now);
        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now);
        repository.save(held);
        importJobRepository.save(job);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "REJECTED",
                from.name(), held.getStatus().name(), reason));
        auditService.record(actingAdminId, "TRUST_REVIEW_REJECTED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "reason", reason == null ? "" : reason));
        return HeldStatementDto.from(held);
    }

    // --- internals -------------------------------------------------------------------------------

    private HeldStatement require(String heldId) {
        return repository.findByHeldId(heldId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such held statement."));
    }

    /**
     * The job the hold is about. Its absence is a 409 rather than a 500 because the only way it
     * happens is a job deleted underneath a review -- a state an operator can understand and
     * nothing here can fix.
     */
    private ImportJob requireJob(HeldStatement held) {
        return importJobRepository.findById(held.getImportJobId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "The import behind " + held.getHeldId() + " no longer exists."));
    }

    /**
     * 409 naming the state, the same convention {@code AdminHeldImportService.reprocess} uses, so
     * an operator can tell "someone already decided this" from "this cannot be decided".
     *
     * <p>Checked here as well as in the entity so a double-clicked button gets an explainable
     * conflict rather than a 500 from an IllegalStateException.
     */
    private static void refuseIfResolved(HeldStatement held, String verb) {
        if (held.getStatus().isResolved()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    held.getHeldId() + " was already " + held.getStatus() + "; it cannot be "
                            + verb + " again.");
        }
    }

    private void notifyStatementReady(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                // "bank" is the template's documented fallback, giving "Your bank statement is
                // ready". A missing param would render "{{bank}}" literally to the customer.
                Map.of("bank", "bank")));
    }
}
