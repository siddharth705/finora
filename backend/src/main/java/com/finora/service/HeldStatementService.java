package com.finora.service;

import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the quarantine record for an import the trust predicate held.
 *
 * <p>{@code REQUIRES_NEW} so the hold's own row and its first audit event commit together and
 * independently of the job's transaction. The worker treats a failure here as non-fatal and holds
 * the import anyway, so a half-written hold -- a row with no event, or an event with no row --
 * would be worse than none: the operator queue would show a review with no history explaining it.
 */
@Service
public class HeldStatementService {

    private final HeldStatementRepository repository;
    private final HeldStatementEventRepository eventRepository;
    private final HeldStatementIdGenerator idGenerator;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
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
        repository.save(held);

        // actorId null: the system opened this, not a person. The reasons are recorded here as
        // well as on the row because the row's summary is editable context for an operator, while
        // the event is the immutable record of what the predicate actually said at hold time.
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        return held;
    }
}
