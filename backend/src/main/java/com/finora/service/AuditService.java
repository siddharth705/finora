package com.finora.service;

import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail for actions that matter on a financial platform: authentication events,
 * transaction/account/budget/goal mutations. {@link #record} is the only write point for a new
 * row, and no public API here ever deletes one — a row's existence, and the event fields it
 * records (actor, action, entity, timestamp, correlation id), are permanent once written, or this
 * stops being an audit trail. {@link #redactExpiredMetadata()} is the one narrow, deliberate
 * exception to that: see BH-044's own doc section below for why overwriting just the {@code
 * metadata} payload after a retention window is a different guarantee than the row itself
 * changing, and why it does not weaken the trail's actual purpose.
 *
 * This is wired into the highest-value actions (auth, transactions, accounts) as the
 * demonstrated pattern; extending it to Budget/Goal services is the same three lines
 * repeated — see README "Next Steps".
 *
 * <h2>Retention (BH-044)</h2>
 *
 * <p>{@code audit_logs} has no partitioning or archival, and grows for the lifetime of the
 * deployment. Its {@code metadata} JSONB carries customer financial content: {@code
 * TRANSACTION_DELETED} records {@code amount} and the full {@code description}, {@code
 * BUDGET_UPSERTED} records the limit. That is defensible for an audit trail and it also means this
 * table was, until this redaction sweep existed, a second, indefinitely-retained copy of ledger
 * content readable by any admin holding {@code AUDIT_VIEW}.
 *
 * <p>BH-044's growth-rate half was fixed earlier: {@code ReconciliationService} no longer writes a
 * {@code RECONCILIATION_RUN} row for a run that reclassified nothing, which used to accompany every
 * transaction create, update and delete. <b>The retention half — this section — was the open
 * product decision.</b> Sid's answer, recorded 2026-08-15: the audit EVENT (actor, action, entity,
 * timestamp, correlation id) is kept forever; the {@code metadata} payload is redacted entirely —
 * not truncated, not archived off-database, and not trimmed by a per-field allow-list — replaced
 * with a small marker object, once a row is older than {@link #effectiveRedactionRetention()}. That
 * keeps the trail's own purpose (who did what, when) intact forever while bounding how long the
 * ledger content living inside it stays readable.
 *
 * <p><b>Known gap, not yet resolved:</b> "the event is kept forever" assumes {@code entityId}
 * identifies the row. It doesn't always — {@code Bank}'s natural-key String id doesn't fit the
 * {@code entityId} column, so {@code BANK_CREATED}/{@code BANK_UPDATED}/{@code BANK_DELETED}
 * record {@code entityId = null} and carry the real bank id only inside {@code metadata}
 * ({@code bankId}). {@link AuditLogRepository#findByBankIdInMetadata} — the only path the admin
 * Bank audit tab has to that history — reads it from there. Once this sweep redacts such a row,
 * that lookup can never find it again: the event isn't just thinner, it becomes permanently
 * unreachable through the one feature built to surface it, indistinguishable from a bank that
 * never had any history. This is a real gap in the "kept forever" claim above, not an accepted
 * cost of it — flagged for a decision, not silently absorbed.
 *
 * <p><b>The sweep lives here, beside {@link #record}</b>, as a method this class owns — {@code
 * record} is already the single write point, so a single read/expire point keeps the whole
 * lifecycle in one class. It is deliberately not a bare {@code deleteBy…} on {@code
 * AuditLogRepository}: this trail is append-only by design (see above), so {@link
 * #redactExpiredMetadata()} is a named, audited, policy-driven operation that loads each candidate
 * row and calls {@link AuditLogRepository#save} on it — the same persistence path {@link #record}
 * uses — rather than a bulk statement any caller could reach and misuse.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Replaces the real {@code metadata} payload once a row is redacted. Deliberately minimal —
     *  BH-044's decision is to wipe the whole blob, not keep any subset of the real financial
     *  fields, so this carries nothing beyond "this happened, the detail is gone". */
    static final Map<String, Object> REDACTED_METADATA = Map.of("redacted", true);

    /** Floor under the configured retention window, independent of config — same role as {@link
     *  com.finora.imports.storage.StatementStorageSweepService#MINIMUM_SAFETY_BUFFER} for BH-017.
     *  A misconfigured {@code retention-days} (e.g. 0, or a typo'd small number) must not be able to
     *  redact a row newer than 90 days old: unlike BH-017's 24h buffer, which only guards an
     *  in-flight-request race, this guards a much larger and harder-to-undo mistake — wiping real
     *  financial detail out of the audit trail — so the floor is measured in months, not hours. */
    static final Duration MINIMUM_RETENTION = Duration.ofDays(90);

    private final AuditLogRepository auditLogRepository;

    @Value("${app.audit.redaction.enabled:true}")
    private boolean redactionEnabled;

    /** Sid's product decision (BH-044, 2026-08-15) — see this class's "Retention" doc section. */
    @Value("${app.audit.redaction.retention-days:730}")
    private int retentionDays;

    /** How many candidate rows one redaction pass considers. Same reasoning as {@code
     *  StatementStorageSweepService.batchSize} — a backlog drains across runs rather than in one
     *  unbounded pass — sized larger here because audit_logs is written from roughly 70+ call sites
     *  across ~28 services, so it is a substantially higher-volume table than statement storage. */
    @Value("${app.audit.redaction.batch-size:500}")
    private int redactionBatchSize;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void record(UUID userId, String action, String entityType, UUID entityId, Map<String, Object> metadata) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setMetadata(metadata);
        log.setRequestId(MDC.get("requestId")); // ties this audit entry to the request that caused it
        auditLogRepository.save(log);
    }

    public void record(UUID userId, String action, String entityType, UUID entityId) {
        record(userId, action, entityType, entityId, null);
    }

    // Self-service login history (Phase 2 audit hardening / user-security-center-proposal.md
    // §3.1) -- behind a service method, not a direct repository call from LoginHistoryController,
    // per CODING_STANDARDS.md's "controllers stay thin" rule (LayerDependencyDirectionTest).
    private static final List<String> LOGIN_ACTIONS =
            List.of("USER_LOGIN", "USER_LOGIN_GOOGLE", "USER_LOGIN_APPLE", "LOGIN_FAILED");

    public List<AuditLog> findLoginHistory(UUID userId) {
        return auditLogRepository.findTop50ByUserIdAndActionInOrderByCreatedAtDesc(userId, LOGIN_ACTIONS);
    }

    /**
     * Same as {@link #record}, except the row is committed in its own transaction rather than
     * joining the caller's.
     *
     * <p>Bug found via manual verification of Phase C's export endpoint: {@code
     * DataExportService.buildBundle} is {@code @Transactional(readOnly = true)}, and its
     * wrong-password branch called plain {@code record(...)} immediately before throwing {@link
     * com.finora.exception.ApiException} (a {@code RuntimeException}). Because {@code record}
     * carries no propagation of its own, that write joined the same transaction -- so Spring's
     * default rollback-on-RuntimeException rule discarded the just-written audit row along with
     * everything else the moment the exception propagated. The row was never visible in {@code
     * audit_logs} despite {@code record()} having been called; only a real Postgres transaction
     * (not a mocked repository) can show this at all, which is why no unit test caught it.
     *
     * <p>The same "record a failure, then throw" shape exists at several other call sites across
     * this codebase. A follow-up swept them: {@code UserAccountLifecycleService.deactivate()}
     * carried the identical gap (plain {@code @Transactional}, no {@code noRollbackFor}) and now
     * uses this method too. {@code PasswordChangeService}'s three sites do NOT -- their methods
     * are {@code @Transactional(noRollbackFor = ApiException.class)}, which excludes {@code
     * ApiException} from Spring's rollback rule entirely, so a plain {@link #record} there already
     * commits fine; switching them to this method would only add an unneeded {@code REQUIRES_NEW}
     * transaction. Verified empirically against real Postgres in {@code PasswordChangeServiceIT},
     * not assumed from the annotation alone -- see that test class's own doc comment. A failure
     * this audit trail exists specifically to catch (repeated wrong-password attempts against a
     * password-gated endpoint) is exactly the kind of event that must not silently vanish just
     * because the request that triggered it went on to fail for the reason being recorded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvenOnRollback(UUID userId, String action, String entityType, UUID entityId) {
        record(userId, action, entityType, entityId, null);
    }

    /**
     * The scheduled trigger for BH-044's redaction sweep. Gated by a flag for the same reason
     * {@code StatementStorageSweepService.scheduledSweep} is: tests need this deterministic, and a
     * background thread rewriting audit rows mid-test is exactly the cross-test pollution BH-058
     * was about. {@code application-test.yml} turns it off; tests call {@link
     * #redactExpiredMetadata()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, matching the same precedent: the next run
     * starts after the previous one finishes, so a slow pass cannot pile up overlapping runs.
     *
     * <p>Interval and initial delay default shorter than {@code StatementStorageSweepService}'s (1h
     * vs 6h, same 5min initial delay) for the same volume reason {@link #redactionBatchSize} is
     * larger: audit_logs accumulates candidates faster than statement_imports does. Neither needs
     * to run promptly in an absolute sense — a 730-day-old row redacted a few hours late is
     * immaterial — this is sized only so a backlog does not grow faster than it drains.
     */
    @Scheduled(fixedDelayString = "${app.audit.redaction.interval-ms:3600000}",
            initialDelayString = "${app.audit.redaction.initial-delay-ms:300000}")
    public void scheduledRedaction() {
        if (!redactionEnabled) return;
        RedactionResult result = redactExpiredMetadata();
        if (result.redacted() > 0 || result.skipped() > 0 || result.failed() > 0) {
            log.info("Audit log redaction: {} row(s) redacted, {} skipped, {} failed.",
                    result.redacted(), result.skipped(), result.failed());
        }
    }

    /**
     * Runs one redaction pass, independent of the scheduler so tests can call it directly. Selects
     * up to {@link #redactionBatchSize} rows older than {@link #effectiveRedactionRetention()} that
     * have not already been redacted, and for each one, replaces {@code metadata} with {@link
     * #REDACTED_METADATA} and stamps {@code redactedAt}. Every other field — {@code action}, {@code
     * entityType}, {@code entityId}, {@code userId}, {@code requestId}, {@code createdAt} — is left
     * untouched, per BH-044's decision that the event itself is kept forever.
     *
     * <p>{@link AuditLogRepository#existsByIdAndRedactedAtIsNull} re-checks each candidate fresh,
     * against the database, immediately before mutating it — mirroring {@code
     * StatementStorageSweepService.sweep}'s own fresh re-check right before its irreversible
     * action. This is deliberately NOT the same as trusting the in-memory candidate object {@link
     * AuditLogRepository#findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc} returned —
     * that object's {@code redactedAt} was already guaranteed null at SELECT time and nothing
     * re-fetches it, so checking it again here would never catch anything. The discovery query can
     * be stale by the time execution reaches a given row — most plausibly a second app instance's
     * redaction pass reaching the same row first, since Railway can run more than one instance and
     * {@code fixedDelay} only prevents overlap within one JVM — and only a genuinely fresh read
     * defends against that. Skipped, not failed: nothing went wrong, the row is simply already
     * handled.
     *
     * <p>One row failing to save must not abort the rest of the batch — same discipline {@code
     * StatementStorageSweepService.sweep} uses for a failed delete: logged and skipped, and the row
     * stays a candidate for the next scheduled run since its {@code redactedAt} was never set. This
     * method deliberately carries no {@code @Transactional} of its own, which is what makes that
     * isolation real rather than cosmetic: {@link AuditLogRepository#save} is itself transactional
     * (inherited from {@code SimpleJpaRepository}), so with no surrounding transaction each row
     * commits independently the moment its own {@code save} returns. Wrapping this whole method in
     * one transaction would mean one row's exception marks that single transaction rollback-only,
     * silently discarding every already-redacted row in the same batch on commit — the try/catch
     * below would appear to isolate failures while actually doing nothing.
     *
     * @return how many rows were redacted, already-redacted-and-skipped, or failed, so a caller or
     *         test can see the pass did something
     */
    public RedactionResult redactExpiredMetadata() {
        Instant cutoff = Instant.now().minus(effectiveRedactionRetention());
        // PageRequest.of throws IllegalArgumentException on a non-positive page size -- unlike
        // retentionDays, which MINIMUM_RETENTION floors against a misconfiguration, a bad
        // batch-size (0, negative) would otherwise throw here uncaught on every scheduled run,
        // permanently breaking the sweep before it ever reaches a row.
        int batchSize = Math.max(1, redactionBatchSize);
        List<AuditLog> candidates = auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(
                cutoff, PageRequest.of(0, batchSize));

        int redacted = 0;
        int skipped = 0;
        int failed = 0;
        for (AuditLog entry : candidates) {
            // The fresh re-check is inside the try/catch too, not just the mutation -- a transient
            // failure on this call (connection blip, timeout) must not abort every remaining
            // candidate in the batch uncaught, the same "one bad row can't take down the rest"
            // discipline the mutation itself already gets below.
            try {
                // Fresh re-check against the database, not the in-memory object the discovery
                // query above already returned -- see AuditLogRepository
                // .existsByIdAndRedactedAtIsNull's own doc for why that distinction is
                // load-bearing, not cosmetic.
                if (!auditLogRepository.existsByIdAndRedactedAtIsNull(entry.getId())) {
                    skipped++;
                    continue;
                }
                entry.setMetadata(REDACTED_METADATA);
                entry.setRedactedAt(Instant.now());
                auditLogRepository.save(entry);
                redacted++;
            } catch (RuntimeException e) {
                // One bad row must not abort the rest of the batch -- log and move on. The row's
                // redactedAt was never set, so it remains a candidate and is retried next run.
                failed++;
                log.error("Failed to redact audit log {}: {}", entry.getId(), e.getMessage(), e);
            }
        }
        return new RedactionResult(redacted, skipped, failed);
    }

    /** See {@link #MINIMUM_RETENTION}. */
    private Duration effectiveRedactionRetention() {
        Duration configured = Duration.ofDays(retentionDays);
        return configured.compareTo(MINIMUM_RETENTION) > 0 ? configured : MINIMUM_RETENTION;
    }

    public record RedactionResult(int redacted, int skipped, int failed) {
    }
}
