package com.finora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.HeldStatementDetailDto;
import com.finora.dto.HeldStatementDetailDto.EventView;
import com.finora.dto.HeldStatementDetailDto.FindingView;
import com.finora.dto.HeldStatementDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.jobs.ParserVersionProvider;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.dto.HeldStatementRerunResultDto;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    private static final Logger log = LoggerFactory.getLogger(HeldStatementService.class);

    /** The working queue: everything an operator can still act on. */
    private static final List<HeldStatement.Status> OPEN = List.of(
            HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
            HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT);

    private final HeldStatementRepository repository;
    private final HeldStatementEventRepository eventRepository;
    private final HeldStatementIdGenerator idGenerator;
    private final ImportJobRepository importJobRepository;
    private final ImportVerificationFindingRepository findingRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ImportSessionService importSessionService;
    private final ObjectMapper objectMapper;
    private final StatementContentService statementContentService;
    private final ImportService importService;
    private final ParserVersionProvider parserVersionProvider;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator,
                                ImportJobRepository importJobRepository,
                                ImportVerificationFindingRepository findingRepository,
                                AuditService auditService,
                                NotificationService notificationService,
                                ImportSessionService importSessionService,
                                ObjectMapper objectMapper,
                                StatementContentService statementContentService,
                                ImportService importService,
                                ParserVersionProvider parserVersionProvider) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.importJobRepository = importJobRepository;
        this.findingRepository = findingRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.importSessionService = importSessionService;
        this.objectMapper = objectMapper;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.parserVersionProvider = parserVersionProvider;
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
    public PagedResponse<HeldStatementDto> list(int page, int size, HeldStatementFilter filter) {
        // Resolved here, not in the repository: JPQL arithmetic against CURRENT_TIMESTAMP is not
        // something every JPA provider evaluates the same way, and a fixed Instant computed once
        // per call is also what makes the query's own "older than" reasoning testable without a
        // clock dependency inside the query itself.
        Instant olderThan = filter.olderThanHours() == null
                ? null : Instant.now().minus(Duration.ofHours(filter.olderThanHours()));
        Page<HeldStatement> result = repository.findForAdmin(OPEN, filter.status(), filter.bankName(),
                olderThan, filter.assignedEngineerId(),
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
        return PagedResponse.of(result.map(HeldStatementDto::from));
    }

    /**
     * The summary plus the evidence behind {@code triggerSummary} and the hold's own history.
     *
     * <p>Findings come from {@code import_verification_findings}, keyed by {@code import_job_id} --
     * the same table and the same allowlisted, statement-content-free shape {@code
     * ImportTraceService} already exposes to engineers diagnosing a parser failure. This is not a
     * new signal: it is the printed-versus-parsed evidence the trust predicate itself read to
     * decide to hold, surfaced as the numbers rather than as a sentence about them.
     */
    @Transactional(readOnly = true)
    public HeldStatementDetailDto detail(String heldId) {
        HeldStatement held = require(heldId);
        List<ImportVerificationFinding> rows =
                findingRepository.findByImportJobIdOrderBySectionIndexAscRuleAsc(held.getImportJobId());
        List<HeldStatementEvent> events =
                eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        return new HeldStatementDetailDto(HeldStatementDto.from(held), findings(rows), timeline(events));
    }

    private List<FindingView> findings(List<ImportVerificationFinding> rows) {
        return rows.stream()
                .map(row -> new FindingView(row.getSectionIndex(), row.getRule(), row.getOutcome(),
                        readDetails(row), row.getCreatedAt()))
                .toList();
    }

    /** Unreadable details degrade one field rather than failing the whole detail view -- same call
     *  {@code ImportTraceService.readDetails} makes about a row a future version wrote in a shape
     *  this one does not expect. */
    private Map<String, Object> readDetails(ImportVerificationFinding row) {
        String json = row.getDetailsJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Held statement finding {} has unreadable details; reporting the rest of the "
                    + "finding without them", row.getId(), e);
            return Map.of();
        }
    }

    private List<EventView> timeline(List<HeldStatementEvent> events) {
        return events.stream()
                .map(e -> new EventView(e.getEventType(), e.getFromStatus(), e.getToStatus(),
                        e.getNotes(), e.getActorId(), e.getCreatedAt()))
                .toList();
    }

    // --- assignment (brief Phase 6, pulled forward by the owner's decision, 2026-09-04) -----------

    /**
     * Assigns the hold to an engineer -- {@code engineerId} null means "Assign to Me", the common
     * case, which must not require typing an id.
     *
     * <p>The entity's own {@link HeldStatement#assign} already allows reassigning an unresolved
     * hold (that guard and its test predate this task); this is the service, endpoint and audit
     * around it, not a new state-machine rule.
     */
    @Transactional
    public HeldStatementDto assign(UUID actingAdminId, String heldId, UUID engineerId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "assigned");

        HeldStatement.Status from = held.getStatus();
        UUID assignee = engineerId != null ? engineerId : actingAdminId;
        held.assign(assignee, Instant.now());
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "ASSIGNED",
                from.name(), held.getStatus().name(), null));
        auditService.record(actingAdminId, "TRUST_REVIEW_ASSIGNED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "assignedTo", assignee.toString()));
        return HeldStatementDto.from(held);
    }

    /**
     * Moves a hold into active investigation.
     *
     * <p>The entity's own {@code startInvestigation} carries no source-status guard beyond {@code
     * refuseIfResolved} -- Plan 1's own test only exercises it after {@code assign}, but nothing
     * stops calling this on a HELD row that was never assigned first, and this does not invent a
     * restriction the entity's state machine does not have. An operator who can already see the
     * extraction going straight to investigating it, without a separate assignment step, is a
     * legitimate way to work the queue, not a gap.
     */
    @Transactional
    public HeldStatementDto startInvestigation(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "moved back into investigation");

        HeldStatement.Status from = held.getStatus();
        held.startInvestigation();
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "INVESTIGATING",
                from.name(), held.getStatus().name(), null));
        auditService.record(actingAdminId, "TRUST_REVIEW_INVESTIGATION_STARTED", "HeldStatement",
                held.getId(), Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    /**
     * Replaces the engineer's write-up wholesale, same as {@link HeldStatement#addNotes} itself
     * documents -- the history of what it said before lives in the event this writes, not in a
     * second notes column.
     *
     * <p>Deliberately not guarded by {@code refuseIfResolved}: the entity's own {@code addNotes}
     * carries no such guard, and a closing note explaining the final reasoning after a decision is
     * a legitimate thing to record, not a state-machine violation to prevent.
     */
    @Transactional
    public HeldStatementDto addNotes(UUID actingAdminId, String heldId, String notes) {
        HeldStatement held = require(heldId);
        held.addNotes(notes);
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "NOTES_UPDATED",
                null, null, notes));
        auditService.record(actingAdminId, "TRUST_REVIEW_NOTES_UPDATED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    /** Records what an engineer found and where the fix landed. Same replace-wholesale semantics
     *  as {@link #addNotes}, and deliberately not guarded by {@code refuseIfResolved} for the
     *  identical reason. */
    @Transactional
    public HeldStatementDto recordFindings(UUID actingAdminId, String heldId, String rootCause,
                                           String fixReference) {
        HeldStatement held = require(heldId);
        held.recordEngineerFindings(rootCause, fixReference);
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "FINDINGS_UPDATED",
                null, null, rootCause));
        auditService.record(actingAdminId, "TRUST_REVIEW_FINDINGS_UPDATED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    private static final String PARSER_RERUN_EVENT = "PARSER_RERUN";

    /**
     * Re-parses this hold's original bytes with the CURRENT parser build and reports whether the
     * trust predicate would still flag it.
     *
     * <p>Reads through {@link ImportService#dryRunParse} exclusively -- see that method's own doc
     * for why the live staging path is unsafe here: it would delete the {@code ImportSession} a
     * later {@link #approve} still needs.
     *
     * <p>{@code today} is {@code held.getCreatedAt()}'s date, not the date this method runs on --
     * using the current date would let a genuinely future-dated statement period stop being
     * flagged for no reason but calendar drift, which would misreport a rerun as having fixed
     * something no parser change touched.
     *
     * <p>Writes exactly one thing beyond the hold's own status: a {@code PARSER_RERUN} event. It
     * never calls {@code ImportVerificationRecorder.recordForJob} -- {@code
     * ImportVerificationFinding} rows are immutable and carry no attempt/version column, so a
     * second write against the same {@code importJobId} would sit indistinguishably beside the
     * original hold's evidence in {@link #detail}.
     *
     * <p>Clearing moves the hold to {@code READY_FOR_IMPORT}, never straight to {@code IMPORTED}
     * -- a human still approves. Calling this again on an already-{@code READY_FOR_IMPORT} hold is
     * legal and idempotent: {@code HeldStatement.markReadyForImport}'s only guard is {@code
     * refuseIfResolved}, which does not single out a required starting status.
     *
     * <p>The clearing path is protected against a concurrent {@link #approve}/{@link #reject}/etc.
     * on the same hold by {@code HeldStatement}'s {@code @Version} column (V151): a losing
     * concurrent write there throws {@code ObjectOptimisticLockingFailureException}, mapped to a
     * 409 by {@code GlobalExceptionHandler.handleOptimisticLock} -- never a silent overwrite of
     * whichever admin action committed first. <b>The still-held path is not equally protected</b>:
     * when {@code decision.hold()} stays true, {@code held} is never re-saved (nothing about it
     * changed), so no version check fires. If a concurrent resolution wins a genuine race against
     * this method's own stale-in-transaction read, the {@code PARSER_RERUN} event this branch
     * writes can trail the resolution -- recording {@code from}/{@code to} as the hold's
     * pre-resolution status even though the row is by then already resolved. This does not corrupt
     * the hold's actual status (this branch never writes one), only its own event's historical
     * accuracy; a narrow, low-severity gap left open rather than pulling in
     * {@code EntityManager.lock(..., LockModeType.OPTIMISTIC_FORCE_INCREMENT)} for a race this
     * narrow.
     */
    @Transactional
    public HeldStatementRerunResultDto rerunParser(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "re-parsed");
        ImportJob job = requireJob(held);

        byte[] content = statementContentService.read(job);
        ImportService.DryRunResult dryRun;
        String extractionError = null;
        try {
            dryRun = importService.dryRunParse(job.getUserId(), job.getFileName(), content, job.getSourceFormat());
        } catch (ApiException e) {
            dryRun = new ImportService.DryRunResult(List.of(), List.of());
            String code = e.getCode() != null ? e.getCode().name() : "UNKNOWN";
            extractionError = code + ": " + e.getMessage();
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not re-read this statement: " + e.getMessage());
        }

        java.time.LocalDate anchoredToday = held.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        HoldDecision decision = extractionError != null
                ? new HoldDecision(true, List.of("Current parser build fails to extract this document ("
                        + extractionError + ")"))
                : TrustPredicate.evaluate(dryRun.verificationReports(), dryRun.statementPeriods(), anchoredToday);

        String previousVersion = held.getParserVersion();
        String currentVersion = parserVersionProvider.current();
        boolean versionChanged = currentVersion != null && !currentVersion.equals(previousVersion);
        HeldStatement.Status from = held.getStatus();
        if (!decision.hold()) {
            held.markReadyForImport(Instant.now());
            repository.save(held);
        }

        String summaryNote = (decision.hold()
                ? "Still held: " + String.join("; ", decision.reasons())
                : "Clears under the current parser build.")
                + " Parser version: " + previousVersion + " -> " + currentVersion
                + " (" + (versionChanged ? "changed" : "unchanged") + ").";
        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, PARSER_RERUN_EVENT,
                from.name(), held.getStatus().name(), summaryNote));
        auditService.record(actingAdminId, "TRUST_REVIEW_PARSER_RERUN", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "stillHeld", decision.hold(),
                        "previousParserVersion", String.valueOf(previousVersion),
                        "currentParserVersion", String.valueOf(currentVersion),
                        "parserVersionChanged", versionChanged));

        return new HeldStatementRerunResultDto(previousVersion, currentVersion, versionChanged,
                decision.hold(), decision.reasons(), HeldStatementDto.from(held));
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

    /** What the download endpoint hands back -- everything the controller needs to set the
     *  response headers, in one value. Same shape as {@code StatementImportService.FileDownload},
     *  for the same reason. */
    public record DownloadedStatement(String fileName, byte[] content, String contentType) {}

    /**
     * The one place in the product that hands a customer's bank statement to a member of staff.
     *
     * <p>Audited BEFORE the bytes are read, not after -- a failed transfer (a storage outage, a
     * decrypt failure) must still leave a record that the attempt was made, since the attempt
     * itself is the sensitive event, not only a successful one. The role gate that makes this safe
     * to expose at all lives on the controller, one layer up: {@code TRUST_REVIEW_MANAGE} alone
     * would let anyone who can work the queue reach this method, and that permission is grantable
     * to a future support role that must never receive a customer's statement -- see the
     * repository owner's decision, 2026-09-04, in the Plan 2 document.
     *
     * <p>Deliberately plain {@code @Transactional}, not {@code readOnly = true}, even though the
     * method never mutates a row -- it writes one, the audit entry. {@code AdminNotificationService
     * .detail}'s own doc already caught this exact bug once: a read-only transaction sets
     * Hibernate's flush mode to {@code MANUAL}, so the audit row registered via {@code
     * auditLogRepository.save()} is silently never flushed to the database -- no exception, the
     * row just never existed. Caught here by {@code everyDownloadIsAudited} actually querying
     * Postgres for the row rather than asserting a mock was called.
     */
    @Transactional
    public DownloadedStatement download(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        ImportJob job = requireJob(held);

        auditService.record(actingAdminId, "TRUST_REVIEW_DOCUMENT_DOWNLOADED", "HeldStatement",
                held.getId(), Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));

        byte[] content = statementContentService.read(job);
        return new DownloadedStatement(job.getFileName(), content, contentTypeFor(job.getSourceFormat()));
    }

    /** Same switch {@code StatementImportService.contentTypeFor} makes, over the formats this
     *  system actually stores -- not a filename-extension lookup, which is attacker-influenced. */
    private static String contentTypeFor(String sourceFormat) {
        if (sourceFormat == null) return "application/octet-stream";
        return switch (sourceFormat.toUpperCase()) {
            case "CSV" -> "text/csv";
            case "PDF" -> "application/pdf";
            default -> "application/octet-stream";
        };
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
