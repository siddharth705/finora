package com.finora.imports.storage;

import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BH-017. Reclaims R2/filesystem objects that no row references, once they have been that way
 * for at least the configured re-importability window (90 days by default).
 *
 * <h2>Why this exists</h2>
 * Three code paths drop a DB row that carries a {@code content_hash}/{@code object_key}: the 48h
 * {@code import_sessions} TTL sweep ({@code ImportSessionService.sweepExpiredSessions}), a user
 * deleting a statement ({@code StatementImportService.delete}, a soft delete), and
 * {@code ON DELETE CASCADE} on user deletion. None of them ever touched the underlying object, so
 * once {@code app.statement-storage.provider} is actually configured (PR #67), the documented
 * retention window was fiction: the row disappears, the object stays forever.
 *
 * <p>A third table holds references too, discovered after production evidence surfaced a real
 * FAILED {@code import_jobs} row whose object had no other live reference at all: async imports
 * ({@code ImportJobService}) write to storage before a job is even queued, so a job that later
 * fails still names a real object, one a future "retry without re-upload" needs intact. Neither
 * {@code statement_imports} nor {@code import_sessions} ever gets a row for work that failed
 * before producing one, so until {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}
 * joined this check, a failed job's bytes survived only by the accident of some other reference
 * existing -- never because the failed job itself counted as one.
 *
 * <p>A job the user cancelled before staging finished (also possible with no {@code
 * import_sessions} row ever created -- see {@code ImportJob#isCancellable}) has the identical
 * absence of any other reference, but does NOT get the identical protection: {@link
 * #IMPORT_JOB_EXCLUDED_STATUSES} excludes CANCELLED alongside COMPLETED, not FAILED alongside
 * COMPLETED. Retaining a FAILED job's object trades storage for a real, if not yet built, product
 * need -- retry without re-upload. A CANCELLED job has no such need: the user chose to stop, and
 * nothing in this codebase offers to resume a cancelled import. Protecting it anyway would spend
 * the identical unbounded, never-expiring cost (see the next paragraph) for no corresponding
 * benefit, so it follows the object's normal lifecycle instead, exactly like COMPLETED.
 *
 * <p><b>Accepted trade-off: no TTL on FAILED's protection.</b> {@code import_jobs} rows never
 * expire on their own -- see {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}'s own doc
 * -- so a FAILED job's object is retained for as long as that row exists, which today is
 * indefinitely. This is intentional, not a gap: it means a failed import stays
 * retryable-without-reupload no matter how old, at the cost of one object per FAILED job ever
 * occurring. Revisit if that cost becomes real -- it would need a bounded window on this check, or
 * a cleanup mechanism for {@code import_jobs} rows themselves, neither of which exists today.
 *
 * <h2>Reference counting, not delete-on-row-expiry</h2>
 * Sid decided explicitly against an R2 lifecycle rule or an immediate delete-on-row-expiry, because
 * objects are shared by design (docs/engineering/statement-storage-migration.md §2.1, §3.2): a
 * staged session and the import it confirms into hold identical bytes and resolve to the same
 * object, and every account section of a composite statement plus every re-import shares one
 * object too. A row disappearing says nothing about whether the object is still needed -- only
 * the ABSENCE of every referencing row, across all three of {@code statement_imports},
 * {@code import_sessions}, and {@code import_jobs}, does.
 *
 * <h2>What "eligible" means here</h2>
 * A candidate {@code (content_hash, object_key)} comes from
 * {@link StatementImportRepository#findObjectsUnreferencedSince}: a soft-deleted
 * {@code statement_imports} row whose {@code deleted_at} is older than the retention window. For
 * each candidate, this service re-checks -- fresh, right before acting -- whether ANY row in ANY
 * of three tables currently references that key
 * ({@link StatementImportRepository#existsByObjectKey}, which respects the entity's
 * {@code @SQLRestriction} and so only counts LIVE rows, OR'd with
 * {@link ImportSessionRepository#existsByObjectKey}, which has no lifecycle state to exclude, OR'd
 * with {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn} against
 * {@link #IMPORT_JOB_EXCLUDED_STATUSES} -- see that method's own doc for why COMPLETED and
 * CANCELLED are excluded rather than checked like the others).
 * Only when all three say no does {@link StatementStorage#delete} get called.
 *
 * <h2>A known, deliberate gap</h2>
 * This can only discover candidates that leave a queryable trace, and only
 * {@code statement_imports} does -- its {@code @SQLDelete} soft-delete keeps the row (and its
 * {@code deleted_at}) forever. {@code import_sessions} has no soft delete and {@code ON DELETE
 * CASCADE} is a database-level cascade that bypasses Hibernate entirely, so content whose ONLY
 * reference was ever an abandoned, never-confirmed session, or a since-deleted user's rows, leaves
 * nothing this query -- or any DB query -- can find once that hard delete has run. Closing that
 * would need either object-listing/metadata support added to {@link StatementStorage} (a larger
 * interface change than BH-017 asked for) or a durable tombstone recorded at the moment such a row
 * is hard-deleted (a behavioural change to the existing, well-tested TTL sweep, which BH-017
 * deliberately does not touch). Flagged rather than guessed at -- see the PR description.
 *
 * <h2>Safety margin</h2>
 * Two layers, mirroring this codebase's existing guards against a race with an in-flight request
 * (the 48h TTL sweep's own reasoning, and {@code claimForConfirmation}'s atomic re-check):
 * <ul>
 *   <li>{@link #MINIMUM_SAFETY_BUFFER} puts a floor under the configured retention window, so even
 *       a misconfigured {@code retention-days} of 0 cannot make an object eligible for deletion
 *       within 24 hours of its last reference disappearing -- far longer than any in-flight
 *       upload, confirm, or download takes.</li>
 *   <li>The reference check is re-run immediately before each individual delete, not once for the
 *       whole batch -- a request that re-uploads identical bytes (creating a fresh reference)
 *       between candidate discovery and this service reaching that candidate is caught by the
 *       fresh check and skipped.</li>
 * </ul>
 *
 * <h2>Guarded by configuration, like the rest of this migration</h2>
 * {@link StatementStorage} is {@link Optional}, exactly as it is in {@link StatementContentService}:
 * with no provider configured there is no bean, {@link #sweep} is a no-op, and nothing here runs
 * against a database that has never had an object-storage-backed row in the first place.
 */
@Component
public class StatementStorageSweepService {

    private static final Logger log = LoggerFactory.getLogger(StatementStorageSweepService.class);

    /** See this class's "Safety margin" doc section. Not configurable -- it exists specifically to
     *  bound how far a bad configuration value could push the effective window down. */
    static final Duration MINIMUM_SAFETY_BUFFER = Duration.ofHours(24);

    /** See this class's "Accepted trade-off" doc section, and
     *  {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}'s own doc, for why these two
     *  statuses -- and only these two -- don't make an import_jobs row count as a live reference.
     *
     *  <p>Note what the shape of this set means for a NEW status: because it names the statuses
     *  that stop protecting an object, anything added to {@link ImportJob.Status} is protected by
     *  default. That is the safe direction, and it is also why the protection is easy to remove by
     *  accident -- an edit that "tidied" this set by adding a hold to it would read as harmless.
     *
     *  <p>{@code HELD_FOR_TRUST_REVIEW} is absent and must stay absent. It is the status most
     *  likely to be added here by mistake, because unlike every other hold its job staged
     *  successfully and looks finished. Its object is the reviewer's only copy of the statement
     *  they are being asked to judge, and a trust hold can outlast a failure by a wide margin --
     *  it waits on a person reading a document, not on a retry timer.
     *  {@code StatementStorageSweepServiceTest#heldForTrustReviewIsNotAnExcludedStatus_soTheReviewersCopySurvives}
     *  fails if this set ever grows to include it. */
    static final Set<ImportJob.Status> IMPORT_JOB_EXCLUDED_STATUSES =
            EnumSet.of(ImportJob.Status.COMPLETED, ImportJob.Status.CANCELLED);

    private final Optional<StatementStorage> storage;
    private final StatementImportRepository statementImportRepository;
    private final ImportSessionRepository importSessionRepository;
    private final ImportJobRepository importJobRepository;

    @Value("${app.statement-storage.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.statement-storage.sweep.retention-days:90}")
    private int retentionDays;

    /** How many candidates one sweep run considers. Same reasoning as
     *  {@code ImportSessionService.CLEANUP_BATCH_SIZE}: a backlog drains across runs rather than in
     *  one unbounded pass. */
    @Value("${app.statement-storage.sweep.batch-size:200}")
    private int batchSize;

    public StatementStorageSweepService(Optional<StatementStorage> storage,
                                         StatementImportRepository statementImportRepository,
                                         ImportSessionRepository importSessionRepository,
                                         ImportJobRepository importJobRepository) {
        this.storage = storage;
        this.importSessionRepository = importSessionRepository;
        this.statementImportRepository = statementImportRepository;
        this.importJobRepository = importJobRepository;
    }

    /**
     * The scheduled trigger. Gated by a flag for the same reason
     * {@code ImportSessionService.scheduledSweep} is: tests need this deterministic, and a
     * background thread deleting objects mid-test is exactly the cross-test pollution BH-058 was
     * about. {@code application-test.yml} turns it off; tests call {@link #sweep()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, matching the same precedent: the next run
     * starts after the previous one finishes, so a slow sweep (network calls to R2, potentially
     * many of them) cannot pile up overlapping runs.
     *
     * <p>The default interval is hours, not minutes -- unlike the 48h session TTL, nothing depends
     * on this running promptly. An object sits at "0 references" for 90 days before it is even a
     * candidate, so a sweep that runs a few hours later or earlier changes nothing observable.
     */
    @Scheduled(fixedDelayString = "${app.statement-storage.sweep.interval-ms:21600000}",
            initialDelayString = "${app.statement-storage.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.swept() > 0 || result.skipped() > 0 || result.failed() > 0) {
            log.info("Statement storage sweep: {} object(s) reclaimed, {} still referenced, {} failed to delete.",
                    result.swept(), result.skipped(), result.failed());
        }
    }

    /**
     * Runs one sweep pass. No-ops -- returns {@link Result#EMPTY} without touching the database --
     * when no storage provider is configured, matching how every other consumer of
     * {@link StatementStorage} behaves when {@code app.statement-storage.provider} is unset.
     *
     * <p>Does not run inside a single database transaction: every step here is either a read or a
     * call to external object storage, never a database write, so there is nothing that needs
     * transactional atomicity across the batch, and holding one open for however long N network
     * calls to R2 take would only cost a connection-pool slot for no benefit.
     *
     * @return how many objects were reclaimed, skipped (still referenced by the time this got to
     *         them), or failed to delete -- so a caller or test can see the sweep did something
     */
    public Result sweep() {
        if (storage.isEmpty()) return Result.EMPTY;

        Instant cutoff = Instant.now().minus(effectiveRetention());
        List<Object[]> candidates = statementImportRepository.findObjectsUnreferencedSince(cutoff, batchSize);

        int swept = 0;
        int skipped = 0;
        int failed = 0;
        for (Object[] row : candidates) {
            String contentHash = (String) row[0];
            String objectKey = (String) row[1];
            Instant lastReferencedAt = Instant.ofEpochMilli((Long) row[2]);

            // The safety-critical check. findObjectsUnreferencedSince() is a discovery query that
            // can be stale by the time execution reaches here -- another statement could have been
            // confirmed with identical bytes (a fresh statement_imports row), a new session staged
            // (a fresh import_sessions row), or an async import queued, failed, or cancelled on the
            // same bytes (a live import_jobs row outside IMPORT_JOB_EXCLUDED_STATUSES) in the
            // meantime. Re-checking fresh, immediately before the irreversible call, is what
            // actually makes this safe -- the same shape of guard as
            // ImportSessionRepository.claimForConfirmation's atomic re-check.
            if (statementImportRepository.existsByObjectKey(objectKey)
                    || importSessionRepository.existsByObjectKey(objectKey)
                    || importJobRepository.existsByObjectKeyAndStatusNotIn(objectKey, IMPORT_JOB_EXCLUDED_STATUSES)) {
                skipped++;
                continue;
            }

            try {
                storage.get().delete(objectKey);
                swept++;
                log.info("Swept unreferenced statement object: key={} hash={} unreferencedSince={} age={}",
                        objectKey, contentHash, lastReferencedAt,
                        Duration.between(lastReferencedAt, Instant.now()));
            } catch (StatementStorageException e) {
                // One bad object must not abort the rest of the batch -- log and move on. The
                // object stays a candidate and is retried on the next scheduled run.
                failed++;
                log.error("Failed to sweep statement object: key={} hash={}: {}", objectKey, contentHash,
                        e.getMessage(), e);
            }
        }
        return new Result(swept, skipped, failed);
    }

    /** See {@link #MINIMUM_SAFETY_BUFFER}. */
    private Duration effectiveRetention() {
        Duration configured = Duration.of(retentionDays, ChronoUnit.DAYS);
        return configured.compareTo(MINIMUM_SAFETY_BUFFER) > 0 ? configured : MINIMUM_SAFETY_BUFFER;
    }

    public record Result(int swept, int skipped, int failed) {
        static final Result EMPTY = new Result(0, 0, 0);
    }
}
