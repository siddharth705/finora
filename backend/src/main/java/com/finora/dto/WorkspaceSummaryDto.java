package com.finora.dto;

import java.util.List;
import java.util.Map;

/** Backs GET /api/v1/workspace/dashboard — Financial Intelligence Workspace, Module 1. See
 *  docs/team-message-financial-intelligence-workspace-kickoff.md and WorkspaceDashboardService. */
public record WorkspaceSummaryDto(
        long totalTransactions,
        long totalAccounts,
        long totalMerchants,
        long learnedMerchants,
        long activeRules,
        long relationships,
        long statementsImported,

        // % of the ledger the engine categorized without a manual correction
        // (!Transaction.categoryManuallySet), i.e. an automation rate, not a measure of whether
        // the category chosen was actually *correct* -- Finora has no ground truth to check
        // automatic categorization against, only whether the user ever felt the need to fix it.
        // Null when there are no transactions yet (nothing to compute a rate over). See
        // WorkspaceDashboardService for why this is the honest definition rather than a name
        // that overclaims certainty the system doesn't have.
        Double categorizationAccuracy,

        // Merchant counts bucketed by their top learned category's confidence: HIGH (>=90),
        // MEDIUM (60-89), LOW (<60), UNCONFIRMED (no confirmed category yet) -- the exact
        // thresholds Merchants.tsx's badge coloring already uses (financial-intelligence-engine-
        // spec.md §6.1), reused here rather than invented fresh for this one tile.
        Map<String, Long> confidenceDistribution,

        // Per-transaction-row counts, not per-pair -- a matched transfer sets BOTH sides'
        // reconciliationStatus to TRANSFER, so transferMatches counts 2 for one real transfer
        // event. Documented here rather than silently divided by 2, since duplicateMatches and
        // refundMatches are genuinely one-row-per-event and shouldn't be made to look
        // inconsistent with transferMatches by an invisible /2.
        long duplicateMatches,
        long transferMatches,
        long refundMatches,
        long recurringTransactions,

        // Latest 5 entries across every AuditService-backed action (see ActivityController's own
        // doc comment for exactly what is and isn't captured yet).
        List<AuditLogDto> recentActivity,

        WorkspaceHealthDto health
) {
    /** "Is this feature actually active/intact for this user" — see WorkspaceDashboardService's
     *  doc comment on this record for exactly what each field does and doesn't check. Not all
     *  five are equally real yet; that's stated explicitly per-field rather than left implicit,
     *  same discipline as categorizationAccuracy above. */
    public record WorkspaceHealthDto(
            boolean rulesEnabled,
            boolean merchantLearningActive,
            boolean reconciliationHealthy,
            boolean recurringDetectionHealthy,
            boolean auditLoggingHealthy
    ) {}
}
