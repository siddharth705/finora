package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AuditLogDto;
import com.finora.repository.AuditLogRepository;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Financial Intelligence Workspace, Module 9 (Activity Timeline) — see
 * docs/team-message-financial-intelligence-workspace-kickoff.md. Self-service counterpart to
 * AdminController's GET /admin/users/{userId}/audit-logs: same repository, same DTO, no
 * @PreAuthorize gate needed since this only ever returns the caller's own history (scoped via
 * CurrentUser, never a path-supplied userId) rather than an arbitrary other user's.
 *
 * Coverage note (do not assume this is a complete activity feed): AuditService is already wired
 * into AccountService, AuthService, OtpService, RelationshipService, RoleService, RuleService,
 * StatementImportService, TransactionService, and MerchantService (rename → MERCHANT_UPDATED,
 * merge → MERCHANT_MERGED, both written to this same AuditLog table alongside
 * MerchantLearningAudit's separate MERGED/LEARNED/CORRECTED/UNDONE trail, which stays scoped to
 * the surviving merchant's own learning history rather than this cross-entity feed), so rule/
 * relationship changes, statement imports/deletions, and merchant renames/merges already show up
 * here today. One real gap remains: ReconciliationService/RecurringService mutate transaction
 * rows in place and never write any event log, so "recent reconciliation events" has no source of
 * truth yet. See task tracking for docs/team-message-financial-intelligence-workspace-kickoff.md's
 * Activity Timeline module for closing that gap.
 */
@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUser currentUser;

    public ActivityController(AuditLogRepository auditLogRepository, CurrentUser currentUser) {
        this.auditLogRepository = auditLogRepository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<AuditLogDto>> list() {
        var logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(currentUser.id()).stream()
                .map(l -> new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt()))
                .toList();
        return ApiResponse.ok(logs);
    }
}
