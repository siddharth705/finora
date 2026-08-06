package com.finora.controller;

import com.finora.dto.PagedResponse;
import com.finora.dto.ApiResponse;
import com.finora.dto.AuditLogDto;
import com.finora.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.finora.util.LikePatterns;

/**
 * The first real RBAC-gated endpoint — everything else in the API only ever operates on the
 * calling user's own data (enforced via CurrentUser, not roles), so there was nothing for
 * @PreAuthorize to meaningfully protect until now. Viewing another user's audit trail is exactly
 * the kind of cross-user access that should require an elevated role, not just "any authenticated
 * user" — a real admin/support use case (investigating a reported incident), not a demo stub.
 *
 * Gated by the AUDIT_VIEW permission rather than hasRole('ADMIN') (docs/engineering-directive-
 * phase1.md, Priority 2: "permissions should control access instead of hardcoded role checks").
 * A user whose only access is the legacy `role = "ADMIN"` string still gets this exactly as
 * before -- V16__rbac_roles_permissions.sql seeds an ADMIN Role that includes AUDIT_VIEW, and
 * AuthorizationService resolves that legacy string against it automatically, so this change is
 * behavior-preserving, not a silent tightening or loosening of who can reach this endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AdminController {

    private final AuditLogRepository auditLogRepository;

    public AdminController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/users/{userId}/audit-logs")
    public ApiResponse<List<AuditLogDto>> auditLogsForUser(@PathVariable UUID userId) {
        var logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.ok(logs);
    }

    /**
     * The global (cross-user) activity feed for the admin portal's Audit Log page -- distinct
     * from auditLogsForUser above, which is scoped to one account someone is specifically
     * investigating. Paginated from the start (see AuditLogRepository.search) since this has no
     * natural upper bound the way a single user's history does.
     *
     * Admin Portal Phase 5 (Shared Filtering Framework) -- q/dateFrom/dateTo/sort are all
     * optional, same null-means-"don't filter" convention TransactionController.search and
     * UserRepository.search already use. dateTo is treated as inclusive of the whole day (rolled
     * forward to the next day's midnight) rather than midnight of that day itself, since an admin
     * picking "to: today" in a date-range filter expects today's entries to actually show up.
     */
    @GetMapping("/audit-logs")
    public ApiResponse<PagedResponse<AuditLogDto>> globalAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "desc") String sortDir) {
        int safeSize = com.finora.util.PageBounds.safeSize(size);
        int safePage = com.finora.util.PageBounds.safePage(page);
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, "createdAt"));

        // Escaped for LIKE -- see LikePatterns. The audit Activity Feed searches action and
        // entityType, where an underscore is ordinary ("USER_LOGIN"), so an unescaped _ made
        // every such search quietly over-match.
        String trimmedQ = (q != null && !q.isBlank()) ? LikePatterns.escape(q.trim()) : null;
        Instant from = dateFrom != null ? dateFrom.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant to = dateTo != null ? dateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        var pageResult = auditLogRepository.search(trimmedQ, from, to, pageable);
        return ApiResponse.ok(PagedResponse.of(pageResult.map(this::toDto)));
    }

    private AuditLogDto toDto(com.finora.entity.AuditLog l) {
        return new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt());
    }
}
