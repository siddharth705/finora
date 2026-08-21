package com.finora.dto;

import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.Merchant;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.goals.GoalContribution;
import com.finora.integrations.google.GmailConnection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Download My Data" (Phase C of the account-lifecycle work). One container class holding nested
 * records, same convention as {@code ImportDto}/{@code StatementImportDto}.
 */
public final class DataExportDto {
    private DataExportDto() {}

    /**
     * Full-fidelity net worth snapshot -- {@code NetWorthDto.SnapshotPoint} drops id/totalAssets/
     * totalLiabilities and keeps only date+netWorth, which is the right shape for the Net Worth
     * chart but not for an export that owes the user everything stored about them.
     */
    public record NetWorthSnapshotExportDto(
            UUID id, LocalDate snapshotDate, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netWorth
    ) {
        public static NetWorthSnapshotExportDto from(NetWorthSnapshot s) {
            return new NetWorthSnapshotExportDto(s.getId(), s.getSnapshotDate(), s.getTotalAssets(),
                    s.getTotalLiabilities(), s.getNetWorth());
        }
    }

    /**
     * Identity only -- deliberately excludes {@code topCategory}/{@code topCategoryConfidence}/
     * {@code distribution}, which {@code MerchantDto} derives from {@code
     * MerchantCategoryLearningRepository}, one of the tables this export's locked-in scope
     * explicitly excludes (derived categorization intelligence, not data the user provided).
     */
    public record MerchantExportDto(UUID id, String canonicalName, String logoUrl, String website, String lifecycleStatus) {
        public static MerchantExportDto from(Merchant m) {
            return new MerchantExportDto(m.getId(), m.getCanonicalName(), m.getLogoUrl(), m.getWebsite(),
                    m.getLifecycleStatus().name());
        }
    }

    /**
     * One {@code GmailConnection} row, any status -- live or historical. Carries the same "no
     * credential material" guarantee {@code GmailConnectionStatusDto}'s own doc states, but
     * without the {@code transactionsFound}/{@code needsReview} cross-table stats that only make
     * sense for a single live connection (and would need an extra query per row here).
     */
    public record GmailConnectionExportDto(
            String status, String googleEmail, List<String> grantedScopes,
            Instant connectedAt, Instant lastSyncedAt, Instant lastDiscoveryAt, Instant createdAt
    ) {
        public static GmailConnectionExportDto from(GmailConnection c) {
            List<String> scopes = c.getGrantedScopes() == null || c.getGrantedScopes().isBlank()
                    ? List.of()
                    : List.of(c.getGrantedScopes().split(" "));
            return new GmailConnectionExportDto(c.getStatus().name(), c.getGoogleEmail(), scopes,
                    c.getConnectedAt(), c.getLastSyncedAt(), c.getLastDiscoveryAt(), c.getCreatedAt());
        }
    }

    /**
     * accounts.json entries -- pairs {@code AccountDto} with the deleted marker that reading via
     * {@code AccountRepository.findByUserIdIncludingDeleted} (to mirror the purge's own scope,
     * which includes soft-deleted accounts) requires: {@code AccountDto.status()} is hardcoded
     * {@code "ACTIVE"} by {@code AccountService.listForUser}'s own design (see that method's doc
     * comment), which would misrepresent a soft-deleted account here.
     */
    public record AccountExportEntry(com.finora.accounts.AccountDto account, boolean deleted, Instant deletedAt) {}

    /** One {@code goal_contributions} row -- {@code goalId} is left as a raw FK, not resolved to
     *  the goal's name, the same way transactions.json leaves {@code accountId} raw: the goal it
     *  belongs to (name included) is already in goals.json, one file over. */
    public record GoalContributionExportDto(UUID id, UUID goalId, BigDecimal amount, LocalDate contributedAt) {
        public static GoalContributionExportDto from(GoalContribution c) {
            return new GoalContributionExportDto(c.getId(), c.getGoalId(), c.getAmount(), c.getContributedAt());
        }
    }

    /** D-28 PR4-A. One {@code subscriptions} row -- {@code planId} is resolved to the plan's own
     *  {@code code}/{@code name} rather than left as a raw FK, the same way transactions.json
     *  resolves {@code categoryId} to {@code categoryName}. {@code plan} is null only if the
     *  referenced plan row itself has been removed out from under a historical subscription --
     *  fails soft (null code/name) rather than dropping the subscription row from the export.
     *  {@code deleted}/{@code deletedAt} mirror {@code AccountExportEntry}'s own marker -- read
     *  via {@code SubscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc}, a
     *  soft-deleted subscription must still appear here, explicitly marked, not vanish. */
    public record SubscriptionExportDto(
            UUID id, String planCode, String planName, String status,
            LocalDate startDate, LocalDate endDate, LocalDate renewalDate,
            LocalDate trialStart, LocalDate trialEnd, String paymentProvider, Instant createdAt,
            boolean deleted, Instant deletedAt
    ) {
        public static SubscriptionExportDto from(Subscription s, Plan plan) {
            return new SubscriptionExportDto(s.getId(), plan != null ? plan.getCode() : null,
                    plan != null ? plan.getName() : null, s.getStatus(), s.getStartDate(), s.getEndDate(),
                    s.getRenewalDate(), s.getTrialStart(), s.getTrialEnd(), s.getPaymentProvider(), s.getCreatedAt(),
                    s.getDeletedAt() != null, s.getDeletedAt());
        }
    }

    /** D-28 PR4-A. One {@code plan_changes} row -- your upgrade/downgrade history.
     *  {@code subscriptionId} is left as a raw FK (the subscription it belongs to is one file
     *  over, in subscriptions.json), but {@code fromPlanId}/{@code toPlanId} are resolved to
     *  each plan's own {@code code}/{@code name}, same treatment as {@code
     *  SubscriptionExportDto}'s own {@code planId}. {@code fromPlan} is null both for a
     *  referenced plan row that no longer exists AND for a subscription's very first plan change
     *  (where {@code fromPlanId} itself is null) -- both fail soft rather than dropping the row. */
    public record PlanChangeExportDto(
            UUID id, UUID subscriptionId, String fromPlanCode, String fromPlanName,
            String toPlanCode, String toPlanName, String reason, Instant effectiveAt, Instant createdAt
    ) {
        public static PlanChangeExportDto from(PlanChange pc, Plan fromPlan, Plan toPlan) {
            return new PlanChangeExportDto(pc.getId(), pc.getSubscriptionId(),
                    fromPlan != null ? fromPlan.getCode() : null, fromPlan != null ? fromPlan.getName() : null,
                    toPlan != null ? toPlan.getCode() : null, toPlan != null ? toPlan.getName() : null,
                    pc.getReason(), pc.getEffectiveAt(), pc.getCreatedAt());
        }
    }

    public record Manifest(
            Instant generatedAt, UUID userId, String email,
            List<ManifestEntry> included, List<ManifestEntry> excluded
    ) {}

    public record ManifestEntry(String name, String description, Integer rowCount) {}
}
