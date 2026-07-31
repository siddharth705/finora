package com.finora.dto;

import java.time.Instant;

/**
 * Financial Intelligence Workspace, System Settings module. Deliberately just the one persisted,
 * editable field for now -- every other setting shown on the System Settings page is a static
 * "coming in a future release" label the frontend renders without calling the backend at all.
 * See V22 migration's comment and WorkspaceSettingsService's class comment for the full reasoning.
 */
public record WorkspaceSettingsDto(int autoApplyConfidenceThreshold, Instant updatedAt) {
    public record UpdateRequest(int autoApplyConfidenceThreshold) {}
}
