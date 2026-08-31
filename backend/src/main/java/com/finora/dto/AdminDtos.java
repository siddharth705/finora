package com.finora.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs specific to the admin portal (frontend-admin/) -- grouped in one file the same way
 * AuthDtos groups every auth-flow record, since these are all read by the same small set of
 * admin-only controllers (AdminUserController, AdminStatsController, AdminSystemController) and
 * never by anything in the regular user-facing API.
 */
public class AdminDtos {

    /**
     * One row in the admin Users directory. Deliberately omits passwordHash (never leaves the
     * service layer) and the full roles/permissions detail (RoleDto is heavier than a list view
     * needs) -- roleNames is just the union of the legacy `role` string and any explicit
     * user_roles rows, enough to show "what access does this account have" at a glance.
     */
    public record UserSummaryDto(
            UUID id,
            String email,
            String fullName,
            String phoneNumber,
            boolean phoneVerified,
            String status,
            List<String> roleNames,
            Instant createdAt
    ) {}

    /** UserSummaryDto plus the counts an admin reviewing a single account actually needs --
     *  computed via lightweight COUNT queries (AccountRepository/TransactionRepository
     *  .countByUserId), never by loading every row just to measure how many there are. */
    public record UserDetailDto(
            UUID id,
            String email,
            String fullName,
            String phoneNumber,
            boolean phoneVerified,
            String status,
            List<String> roleNames,
            Instant createdAt,
            Instant updatedAt,
            long accountCount,
            long transactionCount
    ) {}

    /** Aggregate, platform-wide (not per-user) counts for the admin Dashboard's stat tiles.
     *  Gated by PLATFORM_STATS_VIEW -- see V24__admin_platform_stats_permission.sql. */
    public record PlatformStatsDto(
            long totalUsers,
            long activeUsers,
            long suspendedUsers,
            long newUsersLast7Days,
            long newUsersLast30Days,
            long totalAccounts,
            long totalTransactions,
            long totalStatementImports
    ) {}

    /**
     * The Operational Dashboard's single source of truth -- see AdminOperationalDashboardService's
     * class comment. activeUsersToday/transactionsToday/importsToday are real "since midnight
     * UTC" counts, not estimates. importsWithSkippedRowsToday is the honest substitute for
     * "failed imports" -- see StatementImportRepository.countWithSkippedRowsAfter()'s doc
     * comment for why this pipeline has no real FAILED signal to report today. health/alerts
     * both come from AdminHealthRegistryService -- exactly one source of truth for "is something
     * wrong," not two that could disagree. inactiveUsersLast7Days is the inverse of
     * activeUsersToday's own query -- a user who predates the 7-day window with no USER_LOGIN
     * audit row in it, or none ever (see UserRepository.countWithNoAuditActionSince's own doc
     * comment for why "predates the window" matters -- without it, a signup from an hour ago
     * would count as inactive) -- an Insights & Alerts figure, not a daily-reset tile, so it has
     * no previousDay sibling.
     */
    public record OperationalDashboardDto(
            long totalUsers,
            long activeUsersToday,
            long transactionsToday,
            long importsToday,
            long importsWithSkippedRowsToday,
            long inactiveUsersLast7Days,
            PreviousDayDto previousDay,
            NeedsAttentionDto needsAttention,
            HealthDtos.PlatformHealthDto health,
            List<HealthDtos.AlertDto> alerts,
            List<AuditLogDto> recentActivity
    ) {}

    /**
     * Yesterday's counts for the four stat tiles that reset daily, backing each tile's "vs
     * yesterday" delta on the admin Dashboard. totalUsers has no sibling here and never will --
     * it's a running total, not a daily-reset metric, and "vs yesterday" has no sensible meaning
     * for a monotonically-growing count (see AdminOperationalDashboardService.overview()). Both
     * bounds are calendar-day boundaries in the platform reporting zone, the same midnight-to-
     * midnight window importsToday/activeUsersToday/etc. themselves use, one day earlier.
     */
    public record PreviousDayDto(
            long activeUsers,
            long transactions,
            long imports,
            long importsWithSkippedRows
    ) {}

    /**
     * Every field here is a real, currently-unaddressed count, not a fabricated "things to do"
     * list -- see docs/adr/ discussion and the v56 Dashboard redesign: importsWithSkippedRows is
     * repeated here (also on OperationalDashboardDto directly, kept for the existing StatCard)
     * because it belongs conceptually to both "today's activity" and "needs attention." The
     * other three are the first platform-wide counts against fields that already existed for
     * per-transaction/per-user purposes (TransactionRepository.countByNeedsCategoryReviewTrue/
     * countByIsDuplicateOfIsNotNull, UserRepository.countByLockedUntilAfter) -- nothing invented,
     * nothing that isn't a real, actionable state for an admin to look into.
     */
    public record NeedsAttentionDto(
            long importsWithSkippedRowsToday,
            long lockedAccounts,
            long transactionsNeedingCategoryReview,
            long transactionsFlaggedAsDuplicates
    ) {}

    /**
     * D-27 PR3-D. Activation funnel: how many distinct users have EVER reached each of the four
     * stages the owner named -- signup, first import, first budget, first goal (deliberately NOT
     * "goal achieved", a fifth FinancialJourneyService milestone the owner's own funnel wording
     * didn't ask for). A simple snapshot, not a cohort/time-series -- see the D-27 decision: no
     * scheduled pre-aggregation exists anywhere in this codebase yet, and one live query per
     * admin page-load is the owner's own chosen scope for a first cut.
     *
     * Each count is "ever reached," not "currently has one live" -- a user who created a budget
     * and later deleted it still activated. Raw counts only; percentage-of-signedUp is a client-
     * side computation (same split as DashboardSummaryDto's healthScoreTransactionCount/
     * healthScoreMinTransactions), not duplicated here.
     *
     * Stages are not guaranteed to be subsets of each other -- a user can create a budget without
     * ever importing a statement, since Finora doesn't require an import first. That's a real
     * property of this product, not a query bug, and is left visible rather than corrected into a
     * strictly monotonic funnel that would misrepresent actual usage.
     */
    public record ActivationFunnelDto(
            long signedUp,
            long firstImport,
            long firstBudget,
            long firstGoal
    ) {}

    /**
     * One calendar day of the Platform Activity chart -- see
     * AdminOperationalDashboardService.activityTrend()'s own doc comment for how the 7-day window
     * is built and why each day is queried as its own bounded window rather than bucketed from one
     * bulk fetch. date is a calendar day in the platform reporting zone, not an Instant -- there is
     * no time-of-day component to a daily point.
     */
    public record ActivityTrendPointDto(
            LocalDate date,
            long signups,
            long imports,
            long transactions
    ) {}

    /**
     * Wraps Spring Boot Actuator's own HealthEndpoint bean rather than re-implementing DB/disk
     * checks from scratch (see AdminSystemService) -- status is Actuator's own top-level verdict
     * ("UP"/"DOWN"/...), components is the per-indicator breakdown (db, diskSpace, ...) Actuator
     * already computes. Deliberately not just proxying GET /actuator/health: that endpoint has
     * show-details:never set (application.yml) so it never returns this level of detail over
     * HTTP, by design, for an endpoint with no auth in front of it -- this one sits behind
     * SYSTEM_SETTINGS instead, so showing the detail here is intentional, not a leak.
     */
    public record SystemHealthDto(
            String status,
            Map<String, String> components,
            long uptimeSeconds,
            Instant checkedAt
    ) {}

    // --- Role & Permission CRUD (RoleService) -- name is immutable once created on both of
    // these, same as every other entity's natural key in this codebase (Bank.id, Category.name
    // for system categories, ...); only description is ever updated. ---

    public record CreateRoleRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
                    message = "Use 2-50 characters: uppercase letters, digits, or underscores, starting with a letter")
            String name,
            @jakarta.validation.constraints.NotBlank String description
    ) {}

    public record UpdateRoleRequest(@jakarta.validation.constraints.NotBlank String description) {}

    public record CreatePermissionRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
                    message = "Use 2-50 characters: uppercase letters, digits, or underscores, starting with a letter")
            String name,
            @jakarta.validation.constraints.NotBlank String description
    ) {}

    public record UpdatePermissionRequest(@jakarta.validation.constraints.NotBlank String description) {}

    // --- Act-on-behalf-of-user admin actions (AdminUserService) ---

    /** Admin edits to another user's own profile fields -- deliberately excludes email (the
     *  login identifier and JWT subject; changing it here risks silently breaking that user's
     *  next login) and password (there's no legitimate "admin sets a user's password" flow
     *  without a real password-reset/notification story behind it, which forgotPassword/
     *  resetPassword already provide). Every field optional -- only supplied ones change.
     *
     *  <p>Bug fix: this record declared NO constraints, and AdminUserController.update() carried
     *  no {@code @Valid}, so the admin path validated nothing at any layer while the user-facing
     *  path validated everything. That is also why {@code ValidatedRequestBodyTest} never caught
     *  it -- that test's rule is "a DTO that declares a constraint must be reached through
     *  {@code @Valid}", so a DTO declaring none is invisible to it by construction. The
     *  constraints below are deliberately the SAME shared constants RegisterRequest uses, not
     *  restatements, so the two paths cannot drift.
     *
     *  <p>Bean Validation skips null values, which is exactly the "only supplied fields change"
     *  semantics this record wants -- a null field is absent, a present field must be valid. */
    public record AdminUpdateUserRequest(
            @jakarta.validation.constraints.Pattern(regexp = AuthDtos.FULL_NAME_REGEXP, message = AuthDtos.FULL_NAME_MESSAGE)
            String fullName,
            @jakarta.validation.constraints.Pattern(regexp = AuthDtos.PHONE_REGEXP, message = AuthDtos.PHONE_MESSAGE)
            String phoneNumber,
            @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Low balance threshold can't be negative")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Low balance threshold must be a money amount")
            java.math.BigDecimal lowBalanceThreshold,
            @jakarta.validation.constraints.Size(max = 64, message = "Timezone is too long to be a valid zone id")
            String timezone
    ) {}

    /** Body for POST /admin/users/{id}/reactivate. Optional on every field -- an admin reactivating
     *  a suspended account with no note attached (today's existing behavior) is still a valid call;
     *  a null/blank body maps to a null reason exactly like AdminUpdateUserRequest's null-is-absent
     *  convention above, not a validation error. */
    public record AdminReactivateRequest(
            @jakarta.validation.constraints.Size(max = 500, message = "Reason is too long")
            String reason
    ) {}

    // --- Merchant Intelligence (AdminMerchantStatsService / AdminUserMerchantController) ---

    /** One row in the admin Merchant Intelligence page's platform-wide catalog -- see
     *  MerchantRepository.platformMerchantCounts()'s doc comment for exactly what userCount and
     *  rowCount mean (there's no shared/canonical merchant table today, this is purely an
     *  aggregate view over every user's own private Merchant rows). */
    public record MerchantStatDto(
            String canonicalName,
            long userCount,
            long rowCount
    ) {}

    // --- Gmail Merchant Parser Stats (GmailMerchantStatsService / AdminMerchantStatsController) ---

    /**
     * One row in the admin Merchant Intelligence page's Gmail parser-health section (C6.2) --
     * per authenticated email domain, not the canonical merchant name {@link MerchantStatDto}
     * groups by. See {@code GmailMerchantStatsService} for what each count actually means and why
     * {@code successRate} is nullable.
     *
     * @param domain           the authenticated sending domain -- e.g. {@code amazon.in} -- which
     *                         is the real merchant identity here, the same key
     *                         {@code GmailReviewService} keys the review queue on.
     * @param merchant         a display name, cosmetic only -- see
     *                         {@code GmailReviewService#displayNameFor}.
     * @param parsed           messages a parser successfully extracted and staged.
     * @param parseFailed      messages a parser recognised as receipt-shaped but could not extract
     *                         cleanly -- the "this parser needs updating" signal.
     * @param skippedNotReceipt messages a parser correctly decided were not a receipt at all
     *                         (a shipping update, marketing mail) -- expected traffic, not a fault.
     * @param noParserYet      messages from a trusted domain no {@code MerchantEmailParser} claims
     *                         at all -- coverage gap volume, answering "which parser should we
     *                         write next", not this domain's existing parser's health.
     * @param successRate      {@code parsed / (parsed + parseFailed + skippedNotReceipt)}, or null
     *                         when that denominator is zero -- a domain with only {@code
     *                         noParserYet} traffic has no parser to rate yet, and showing 0% would
     *                         misreport "broken" as what is actually "not built".
     * @param lastSeen         the most recent message processed for this domain, across every
     *                         outcome counted above.
     */
    public record GmailMerchantParserStatDto(
            String domain,
            String merchant,
            long parsed,
            long parseFailed,
            long skippedNotReceipt,
            long noParserYet,
            Double successRate,
            Instant lastSeen
    ) {}

    // --- Learning Engine (AdminLearningStatsService / AdminUserLearningController) ---

    /** Platform-wide aggregate for the admin Learning Engine page. learnedMerchantPairs is a
     *  live COUNT of MerchantCategoryLearning rows (current state, across every user) --
     *  totalConfirmations/correctedCount/resetCount are lifetime counts off the audit trail
     *  (history), same "these two views can legitimately disagree" relationship documented on
     *  MerchantLearningService.summary() for the per-user version of this same distinction.
     *  trend reuses AnalyticsDto.LearningGrowthPoint's exact shape (month, learnedCount,
     *  correctedCount) rather than inventing a platform-only variant -- same x-axis convention,
     *  just summed across every user instead of one. */
    public record LearningPlatformStatsDto(
            long learnedMerchantPairs,
            long totalConfirmations,
            long correctedCount,
            long resetCount,
            List<AnalyticsDto.LearningGrowthPoint> trend
    ) {}

    // --- Reconciliation Monitor (AdminReconciliationStatsService / AdminUserWorkspaceController) ---

    /** Platform-wide breakdown of Transaction.reconciliationStatus, plus the separate isRecurring
     *  flag (not itself a reconciliationStatus value -- see Transaction entity). okCount is
     *  every transaction NOT flagged duplicate/transfer/refund, i.e. what reconciliation left
     *  alone. See TransactionRepository.platformReconciliationStatusCounts()'s doc comment for
     *  why this is one grouped COUNT query rather than a full-table scan. */
    public record ReconciliationStatsDto(
            long okCount,
            long duplicateCount,
            long transferCount,
            long refundCount,
            long recurringCount,
            long totalTransactions
    ) {}

    // --- Platform Analytics (AdminPlatformAnalyticsService / AdminPlatformAnalyticsController) ---

    /** Same exclusion rules as AnalyticsService.activeExpenseTransactions() (no duplicates, no
     *  transfers, no REFUND-status income, EXPENSE rows only) but summed across every user's
     *  transactions instead of one -- see AdminPlatformAnalyticsService's class comment for why
     *  this needs a two-step (group-by-id-in-SQL, then resolve-and-re-group-by-name-in-Java)
     *  aggregation rather than a single grouped query. */
    public record PlatformCategorySpendDto(String categoryName, BigDecimal totalSpend, long transactionCount) {}

    /** Same shape and reasoning as PlatformCategorySpendDto, grouped by merchant instead --
     *  distinct from MerchantStatDto (data footprint: how many users/rows) in that this measures
     *  actual platform-wide spend, which MerchantStatDto deliberately doesn't. */
    public record PlatformMerchantSpendDto(String merchantName, BigDecimal totalSpend, long transactionCount) {}

    public record PlatformAnalyticsDto(
            List<PlatformCategorySpendDto> topCategories,
            List<PlatformMerchantSpendDto> topMerchants
    ) {}

    /**
     * One row in the Recent Imports list (Admin Portal Phase 7) -- the closest real equivalent to
     * a background-job monitor this codebase has. CSV import runs synchronously inside the HTTP
     * request (CsvImportService/StatementImportService), not on a queue or worker, so there is no
     * real job queue to observe -- see StatementImportRepository.findAllByOrderByImportedAtDesc's
     * doc comment. A statement_imports row can only ever represent a completed import (a failed
     * import throws before a row is ever persisted, so there's no real FAILED row to show -- V81
     * removed the status column this table briefly carried for exactly that reason), so this
     * deliberately has no status field to fabricate one -- hadSkippedRows is the one real per-row
     * signal worth surfacing, the same honest proxy the Operational Dashboard's
     * importsWithSkippedRowsToday tile already uses.
     */
    public record RecentImportDto(
            UUID id,
            UUID userId,
            String userEmail,
            String fileName,
            int transactionsImported,
            int transactionsSkipped,
            boolean hadSkippedRows,
            Instant importedAt
    ) {}

    // --- Global Search (AdminSearchService / AdminSearchController) ---

    /** One row in the unified search results list, spanning Users/Merchants/Banks/Global Rules --
     *  type/id/title/subtitle/link is deliberately entity-agnostic so the frontend can render one
     *  grouped dropdown without a switch statement per entity type. link is a frontend-admin
     *  route path (e.g. "/users/{id}"), not a backend API path. See AdminSearchService's class
     *  comment for exactly what's in scope for this first version and what's deliberately
     *  excluded (Transactions, Statement Imports -- no admin page can deep-link to a single one
     *  of either in isolation today). */
    public record SearchResultDto(String type, String id, String title, String subtitle, String link) {}

    // --- Real platform-wide configuration (PlatformSettingsService) -- see V27__platform_settings.sql ---

    public record PlatformSettingsDto(
            boolean registrationsEnabled,
            int maxFailedLoginAttempts,
            int lockoutDurationMinutes,
            Instant updatedAt
    ) {}

    /** Every field optional -- only supplied ones change. Boolean/Integer (not boolean/int) is
     *  deliberate here, unlike everywhere else this convention is used in this file -- a plain
     *  `boolean registrationsEnabled` field could never distinguish "admin didn't touch this
     *  field" from "admin explicitly set it to false," which would make toggling every other
     *  setting silently re-enable registrations (or vice versa) on every single save. */
    public record UpdatePlatformSettingsRequest(
            Boolean registrationsEnabled,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(20) Integer maxFailedLoginAttempts,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1440) Integer lockoutDurationMinutes
    ) {}

    // --- Feature flags (Admin Portal Phase 8, FeatureFlagService / AdminFeatureFlagController) ---

    /** id is the flag's own row id (opaque to callers), key is the stable code checks against via
     *  FeatureFlagRepository.isEnabled(key). See RecurringService.detectForUser for the one real
     *  call site wired to a flag today. */
    public record FeatureFlagDto(String id, String key, String description, boolean enabled, Instant updatedAt) {}

    public record UpdateFeatureFlagRequest(boolean enabled) {}
}
