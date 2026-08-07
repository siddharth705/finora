package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * One document layout, as a row rather than as a {@code GROUP BY}.
 *
 * <p>Milestone 2 item 2. {@code layout_fingerprint} has been persisted on statement imports since
 * V39, so layouts have long been <em>observable</em>; what they were not is <em>curatable</em>.
 * {@code LayoutIntelligenceService} re-derived every answer by grouping statement imports on a
 * string column, which means a layout could not be named, approved, tied to a parser, or carry a
 * first/last-seen of its own -- there was nothing to hang any of that on.
 *
 * <h2>Observed fields and curated fields do not mix</h2>
 *
 * <p>{@code sourceFormat}, {@code parser}, {@code firstSeen}, {@code lastSeen} and
 * {@code observationCount} are written by the engine, once per confirmed import, through the atomic
 * upsert in {@code RegisteredLayoutRepository.observe}. {@code name} and {@code status} are written
 * only by an operator, through {@link #rename} and {@link #moveTo}. Nothing on this class lets an
 * import write a curated field, and the upsert's {@code DO UPDATE} clause names only observed
 * columns -- because a layout an operator marked {@link Status#SUPPORTED} falling back to
 * {@link Status#OBSERVED} on its next import would erode the supported list by using it, silently.
 *
 * <h2>Why first/last-seen live here and not in a query</h2>
 *
 * <p>Statement imports are soft-deleted, and the JPA restriction hides deleted rows from every
 * query the intelligence layer runs. A user deleting their uploads therefore deletes the only
 * record that Finora ever saw their layout. These two columns survive that; the aggregate cannot.
 *
 * <h2>Not the LayoutProfile DocumentContext anticipates</h2>
 *
 * <p>{@code DocumentContext.LAYOUT_FINGERPRINT_VERSION}'s comment describes a future
 * {@code LayoutProfile} mapping one display name to several fingerprint <em>versions</em>. This is
 * not that, and should not be renamed into it: one row here is exactly one fingerprint. A profile
 * grouping several of these rows under one name would sit on top, and stays unbuilt until a v2
 * fingerprint spec exists to make it mean anything.
 */
/*
 * @DynamicUpdate is load-bearing here, not a performance tweak. Hibernate's default UPDATE writes
 * every column, so curating a name would also write back observation_count, first_seen and
 * last_seen as they were when the row was read -- rewinding any observation that landed in between
 * and losing it with no error. With dynamic update the statement contains only the columns that
 * actually changed, so the curated half and the observed half stop being able to overwrite each
 * other. It only holds for an entity mutated while managed, which is why curation goes through
 * LayoutRegistryService's transactional methods rather than saving a detached instance.
 */
@Entity
@Table(name = "layout_registry")
@DynamicUpdate
public class RegisteredLayout {

    /**
     * Where a layout stands with us -- a claim about the parser, never about the document.
     *
     * <p>Not the merchant lifecycle vocabulary from V64 (TEMPORARY/UNDER_REVIEW/APPROVED): there,
     * "approved" is a judgement about a merchant the engine invented. Here the judgement is whether
     * <em>Finora</em> handles this structure, and {@link #SUPPORTED} says that where "approved"
     * would not.
     */
    public enum Status {
        /** Seen by the engine; nobody has looked at it. Every backfilled row, and every new one. */
        OBSERVED,
        /** An operator has picked it up. */
        UNDER_REVIEW,
        /** Finora claims to handle this layout -- Milestone 2 success criterion 4's list, the one
         *  that has to "exist in writing" for the corpus gate to have a subject. */
        SUPPORTED,
        /** Reviewed, and deliberately not claimed. Distinct from {@link #OBSERVED}, which means
         *  only that nobody has decided yet -- collapsing the two would let an unreviewed backlog
         *  read as a set of considered decisions. */
        UNSUPPORTED
    }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 20)
    private String fingerprint;

    /** Null until an operator names it, and deliberately not defaulted to the fingerprint: a
     *  generated placeholder is indistinguishable from a real name, which makes "how many layouts
     *  have we actually identified" unanswerable. */
    @Column
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OBSERVED;

    /** Width matched to {@code statement_imports.source_format}, which V68's backfill copies from. */
    @Column(name = "source_format", length = 10)
    private String sourceFormat;

    @Column(length = 64)
    private String parser;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen = Instant.now();

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen = Instant.now();

    @Column(name = "observation_count", nullable = false)
    private long observationCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RegisteredLayout() {}

    // ------------------------------------------------------------------ curation

    /**
     * Names the layout, or clears the name.
     *
     * <p>Blank collapses to null rather than being stored: an empty string and "never named" are
     * the same state to every reader, and storing both means every caller has to check for both.
     */
    public void rename(String name) {
        this.name = (name == null || name.isBlank()) ? null : name.trim();
        this.updatedAt = Instant.now();
    }

    /**
     * Moves the layout to a curated status.
     *
     * <p>No transition table. The four statuses are a reviewer's opinion, not a lifecycle: a layout
     * marked SUPPORTED that later breaks goes straight back to UNSUPPORTED without passing through
     * review, and a rule forbidding that would only be worked around.
     */
    public void moveTo(Status status) {
        if (status == null) throw new IllegalArgumentException("A layout's status cannot be null");
        this.status = status;
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------ accessors

    public UUID getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getName() { return name; }
    public Status getStatus() { return status; }
    public String getSourceFormat() { return sourceFormat; }
    public String getParser() { return parser; }
    public Instant getFirstSeen() { return firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public long getObservationCount() { return observationCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
