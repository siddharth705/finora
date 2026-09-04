package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One statement quarantined before it reached a user's ledger.
 *
 * <p>Points at the same object key the owning {@link ImportJob} already has -- a workflow state
 * over one shared object, never a copy. Nothing is moved between buckets: the sweep's
 * reference-counting depends on that sharing, and the owning job stays in
 * {@code HELD_FOR_TRUST_REVIEW}, which keeps counting as a live reference.
 *
 * <p>The snapshot fields ({@code parserVersion}, {@code reliabilityStatus}, {@code textSource},
 * {@code headerReconstructionUncertain}, {@code bankName}) are captured at hold time on purpose: a
 * later re-run under a different build has to be comparable against what the original build
 * actually saw, and reading them live would silently answer a different question. {@code
 * bankName} specifically cannot be read live at all -- see the {@code bank_name} column comment
 * in V150 for why {@code import_sessions}, the only other source, cannot be joined instead.
 */
@Entity
@Table(name = "held_statements")
public class HeldStatement {

    /**
     * The review lifecycle. HELD until someone picks it up; IMPORTED or REJECTED once decided.
     *
     * <p>{@link #RESOLVED} is the pair that ends it. Both are final -- see {@link #markImported}
     * for what re-resolving would do.
     */
    public enum Status {
        HELD, ASSIGNED, INVESTIGATING, READY_FOR_IMPORT, IMPORTED, REJECTED;

        /** Decided, and not to be decided again. */
        public static final Set<Status> RESOLVED = EnumSet.of(IMPORTED, REJECTED);

        public boolean isResolved() { return RESOLVED.contains(this); }
    }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "held_id", nullable = false, unique = true)
    private String heldId;

    @Column(name = "import_job_id", nullable = false)
    private UUID importJobId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "statement_object_key", nullable = false)
    private String statementObjectKey;

    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "reliability_status")
    private String reliabilityStatus;

    @Column(name = "text_source")
    private String textSource;

    @Column(name = "header_reconstruction_uncertain")
    private Boolean headerReconstructionUncertain;

    @Column(name = "bank_name")
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.HELD;

    @Column(name = "assigned_engineer_id")
    private UUID assignedEngineerId;

    @Column(name = "trigger_summary")
    private String triggerSummary;

    @Column(name = "engineer_notes")
    private String engineerNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected HeldStatement() {}

    public HeldStatement(String heldId, UUID importJobId, UUID userId, String statementObjectKey,
                         String triggerSummary) {
        this.heldId = heldId;
        this.importJobId = importJobId;
        this.userId = userId;
        this.statementObjectKey = statementObjectKey;
        this.triggerSummary = triggerSummary;
    }

    /** The extraction snapshot, recorded once when the hold is created. */
    public void recordSnapshot(String parserVersion, String reliabilityStatus, String textSource,
                               Boolean headerReconstructionUncertain) {
        this.parserVersion = parserVersion;
        this.reliabilityStatus = reliabilityStatus;
        this.textSource = textSource;
        this.headerReconstructionUncertain = headerReconstructionUncertain;
    }

    /** Set once, when the hold is opened. See the {@code bank_name} column comment for why this is
     *  a snapshot rather than a live read. Null when the parser could not name a bank. */
    public void recordBank(String bankName) { this.bankName = bankName; }

    public void assign(UUID engineerId, Instant now) {
        refuseIfResolved("assigned");
        this.assignedEngineerId = engineerId;
        this.assignedAt = now;
        this.status = Status.ASSIGNED;
    }

    public void startInvestigation() {
        refuseIfResolved("moved back into investigation");
        this.status = Status.INVESTIGATING;
    }

    /** Replaces the notes wholesale -- the engineer is editing their own write-up, and the history
     *  of what it said before lives in {@code held_statement_events}. */
    public void addNotes(String notes) {
        this.engineerNotes = notes;
    }

    public void markReadyForImport(Instant now) {
        refuseIfResolved("marked ready for import");
        this.status = Status.READY_FOR_IMPORT;
        this.readyAt = now;
    }

    /**
     * The release: this statement's staged rows may now reach the user's ledger.
     *
     * <p>Refuses an already-resolved hold. A double-clicked approve button, or a retry of a request
     * whose response was lost, would otherwise overwrite the first admin's {@code resolvedBy} and
     * {@code resolvedAt} -- and, far worse, signal a second import of a statement that already
     * reached the ledger. Same guard, and the same reasoning, as {@link ImportJob#complete}'s
     * refusal to complete a cancelled job.
     *
     * <p>Reaching this straight from HELD is deliberately allowed: not every hold needs an
     * engineer, and an operator who can see the extraction is fine should be able to release it.
     * The rule is one resolution, not one path.
     */
    public void markImported(UUID adminId, Instant now) {
        refuseIfResolved("imported");
        this.status = Status.IMPORTED;
        this.resolvedBy = adminId;
        this.resolvedAt = now;
    }

    /**
     * The other ending: these rows never reach the ledger.
     *
     * <p>Takes no reason, on purpose. There is one notes column, and writing a rejection reason
     * into it would overwrite the engineer's investigation notes -- the findings the rejection was
     * based on -- in a workflow whose entire point is that somebody investigated first. The reason
     * belongs in {@code held_statement_events}, which exists to record who did what and why.
     */
    public void reject(UUID adminId, Instant now) {
        refuseIfResolved("rejected");
        this.status = Status.REJECTED;
        this.resolvedBy = adminId;
        this.resolvedAt = now;
    }

    private void refuseIfResolved(String attempted) {
        if (status.isResolved()) {
            throw new IllegalStateException(
                    "Held statement " + heldId + " was already " + status + "; it cannot be "
                            + attempted + " again.");
        }
    }

    public UUID getId() { return id; }
    public String getHeldId() { return heldId; }
    public UUID getImportJobId() { return importJobId; }
    public UUID getUserId() { return userId; }
    public String getStatementObjectKey() { return statementObjectKey; }
    public String getParserVersion() { return parserVersion; }
    public String getReliabilityStatus() { return reliabilityStatus; }
    public String getTextSource() { return textSource; }
    public Boolean getHeaderReconstructionUncertain() { return headerReconstructionUncertain; }
    public String getBankName() { return bankName; }
    public Status getStatus() { return status; }
    public UUID getAssignedEngineerId() { return assignedEngineerId; }
    public String getTriggerSummary() { return triggerSummary; }
    public String getEngineerNotes() { return engineerNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getResolvedBy() { return resolvedBy; }
}
