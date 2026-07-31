package com.finora.controller;

import com.finora.dto.AdminDtos.ReconciliationStatsDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminReconciliationStatsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide Reconciliation Monitor for the admin console (frontend-admin/). Read-only --
 *  reconciliation itself has no manual trigger or override anywhere in this codebase (see
 *  ReconciliationService's class comment), so unlike Merchant Intelligence/Rule Engine, there is
 *  no corresponding "act on a specific user's reconciliation" controller: per-user visibility is
 *  AdminUserWorkspaceController instead, a straight proxy of the same
 *  WorkspaceDashboardService.summarize() the self-service Workspace Dashboard already used. */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasAuthority('RECONCILIATION_VIEW')")
public class AdminReconciliationStatsController {

    private final AdminReconciliationStatsService adminReconciliationStatsService;

    public AdminReconciliationStatsController(AdminReconciliationStatsService adminReconciliationStatsService) {
        this.adminReconciliationStatsService = adminReconciliationStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<ReconciliationStatsDto> stats() {
        return ApiResponse.ok(adminReconciliationStatsService.platformStats());
    }
}
