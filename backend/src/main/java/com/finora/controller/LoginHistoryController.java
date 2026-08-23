package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AuditLogDto;
import com.finora.entity.AuditLog;
import com.finora.security.CurrentUser;
import com.finora.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Self-service view of the caller's own recent sign-in activity -- successful logins across all
 * three methods plus failed attempts (see AuthService's LOGIN_FAILED/USER_LOGIN* audit calls),
 * so a user can recognize "was that me?" the same way DeviceController already lets them recognize
 * "is that my device?". Distinct from AdminController's auditLogsForUser: that one is cross-user
 * and RBAC-gated for support/investigation; this one is scoped to the caller's own account only,
 * via CurrentUser, the same way every other /users/me endpoint is.
 *
 * user-security-center-proposal.md §3.1 -- option (a) (audit metadata, not a second RefreshToken-
 * derived source of truth). Reuses the existing admin-portal AuditLogDto rather than a new DTO,
 * since the shape is identical and nothing here needs a login-specific field the generic one lacks.
 */
@RestController
@RequestMapping("/api/v1/users/me/login-history")
public class LoginHistoryController {

    private final AuditService auditService;
    private final CurrentUser currentUser;

    public LoginHistoryController(AuditService auditService, CurrentUser currentUser) {
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<AuditLogDto>> list() {
        List<AuditLogDto> history = auditService.findLoginHistory(currentUser.id()).stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.ok(history);
    }

    private AuditLogDto toDto(AuditLog l) {
        return new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt());
    }
}
