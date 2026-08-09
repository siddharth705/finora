package com.finora.service;

import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit trail for actions that matter on a financial platform: authentication
 * events, transaction/account/budget/goal mutations. Deliberately append-only (no update/delete
 * methods) — audit_logs rows should never change once written, or they stop being an audit trail.
 *
 * This is wired into the highest-value actions (auth, transactions, accounts) as the
 * demonstrated pattern; extending it to Budget/Goal services is the same three lines
 * repeated — see README "Next Steps".
 *
 * <h2>SEAM — retention. Not implemented, and deliberately not guessed at (BH-044)</h2>
 *
 * <p><b>{@code audit_logs} has no retention policy, no partitioning and no archival</b>, in the
 * schema or in any migration. It grows for the lifetime of the deployment, and its {@code metadata}
 * JSONB carries customer financial content: {@code TRANSACTION_DELETED} records {@code amount} and
 * the full {@code description}, {@code BUDGET_UPSERTED} records the limit. That is defensible for
 * an audit trail and it also means this table is a second, indefinitely-retained copy of ledger
 * content readable by any admin holding {@code AUDIT_VIEW}.
 *
 * <p>BH-044's growth-rate half is fixed: {@code ReconciliationService} no longer writes a
 * {@code RECONCILIATION_RUN} row for a run that reclassified nothing, which used to accompany every
 * transaction create, update and delete. <b>The retention half is open.</b>
 *
 * <p>It is open because it is a product decision and not an engineering one, and inventing a window
 * here would be the same mistake as guessing a statement-retention period (BH-017, still deferred
 * for the same reason). Answering it needs, at minimum:
 *
 * <ul>
 *   <li>How long a financial audit trail must be retained for whatever regime applies — this is a
 *       compliance answer, not a storage-cost one, and the two point in opposite directions.</li>
 *   <li>Whether a data-subject deletion request must remove these rows, which would make retention
 *       a legal obligation rather than a preference.</li>
 *   <li>Whether the trail should be truncated (drop old rows), redacted (keep the event, drop the
 *       {@code metadata} that carries ledger content), or archived off-database. Those three have
 *       very different costs and only the middle one keeps the trail's own purpose intact.</li>
 * </ul>
 *
 * <p><b>Where a sweep would go when that is decided:</b> here, beside {@link #record}, as a method
 * this class owns — {@code record} is already the single write point, so a single read/expire point
 * keeps the whole lifecycle in one class. It must not be added to {@code AuditLogRepository} as a
 * bare {@code deleteBy…}: this trail is append-only by design (see above), and the deletion that
 * eventually exists should be a named, audited, policy-driven operation rather than a repository
 * method any caller can reach.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

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
}
