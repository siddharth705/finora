package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.WorkspaceSummaryDto;
import com.finora.service.WorkspaceDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Support-assisted Reconciliation Monitor + Workspace Health visibility for a specific user
 * (RECONCILIATION_VIEW). Straight proxy of WorkspaceDashboardService.summarize() -- the exact
 * same read-only aggregation the self-service Workspace Dashboard used before Reconciliation
 * Monitor/Workspace Health moved off the User Portal's main nav, userId sourced from the path
 * instead of CurrentUser. Same thin-proxy pattern as AdminTransactionController /
 * AdminUserMerchantController / AdminUserRuleController. Returns the FULL WorkspaceSummaryDto
 * rather than a reconciliation-only projection: the reconciliation counts and the
 * WorkspaceHealthDto are computed together in one pass over the same transaction list, so
 * splitting them into two calls would mean fetching that list twice for no benefit.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/workspace")
@PreAuthorize("hasAuthority('RECONCILIATION_VIEW')")
public class AdminUserWorkspaceController {

    private final WorkspaceDashboardService workspaceDashboardService;

    public AdminUserWorkspaceController(WorkspaceDashboardService workspaceDashboardService) {
        this.workspaceDashboardService = workspaceDashboardService;
    }

    @GetMapping
    public ApiResponse<WorkspaceSummaryDto> summary(@PathVariable UUID userId) {
        return ApiResponse.ok(workspaceDashboardService.summarize(userId));
    }
}
