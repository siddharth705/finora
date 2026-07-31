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
