package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.dto.WorkspaceSummaryDto;
import com.finora.security.CurrentUser;
import com.finora.service.WorkspaceDashboardService;
import com.finora.service.WorkspaceSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financial Intelligence Workspace, Module 1 (Dashboard) — see
 * docs/team-message-financial-intelligence-workspace-kickoff.md. Distinct namespace
 * (/api/v1/workspace/...) from the existing personal-finance /api/v1/dashboard/summary
 * (DashboardController/DashboardService) on purpose: that one is net worth/health score/spend
 * for the Ledger-facing Dashboard page; this one is operational visibility into the Financial
 * Intelligence Engine itself (merchants, rules, learning, reconciliation) for the new Workspace.
 * Same separation AnalyticsController already keeps from DashboardController.
 */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

    private final WorkspaceDashboardService workspaceDashboardService;
    private final WorkspaceSettingsService workspaceSettingsService;
    private final CurrentUser currentUser;

    public WorkspaceController(WorkspaceDashboardService workspaceDashboardService,
                                WorkspaceSettingsService workspaceSettingsService, CurrentUser currentUser) {
        this.workspaceDashboardService = workspaceDashboardService;
        this.workspaceSettingsService = workspaceSettingsService;
        this.currentUser = currentUser;
    }

    @GetMapping("/dashboard")
    public ApiResponse<WorkspaceSummaryDto> dashboard() {
        return ApiResponse.ok(workspaceDashboardService.summarize(currentUser.id()));
    }

    // Financial Intelligence Workspace, System Settings module -- see WorkspaceSettingsService's
    // class comment for scope.
    @GetMapping("/settings")
    public ApiResponse<WorkspaceSettingsDto> settings() {
        return ApiResponse.ok(workspaceSettingsService.get(currentUser.id()));
    }

    @PutMapping("/settings")
    public ApiResponse<WorkspaceSettingsDto> updateSettings(@RequestBody WorkspaceSettingsDto.UpdateRequest request) {
        return ApiResponse.ok(workspaceSettingsService.update(currentUser.id(), request), "Settings saved");
    }
}
