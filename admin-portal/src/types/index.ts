// Mirrors the backend's DTO shapes exactly (com.finora.dto.*) -- one field for one field, same
// names, so there's no silent drift between what the API actually returns and what this app
// assumes it returns.

export interface UserSummaryDto {
  id: string;
  email: string;
  fullName: string;
  phoneNumber: string | null;
  phoneVerified: boolean;
  // DEACTIVATED/PENDING_DELETION are self-service (User.STATUS_*, V87 migration); DELETED is the
  // terminal, anonymized-tombstone state AccountPurgeSweepService leaves behind -- the row is
  // never actually removed, see User.STATUS_DELETED's own doc comment.
  status: 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED' | 'PENDING_DELETION' | 'DELETED';
  roleNames: string[];
  createdAt: string;
}

export interface UserDetailDto extends UserSummaryDto {
  updatedAt: string;
  accountCount: number;
  transactionCount: number;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PlatformStatsDto {
  totalUsers: number;
  activeUsers: number;
  suspendedUsers: number;
  newUsersLast7Days: number;
  newUsersLast30Days: number;
  totalAccounts: number;
  totalTransactions: number;
  totalStatementImports: number;
}

// --- Operational Dashboard (AdminOperationalDashboardController / com.finora.health.*) ---

export interface ProviderStatusDto {
  name: string;
  category: string;
  status: 'UP' | 'DEGRADED' | 'DOWN';
  detail: string;
}

interface PlatformHealthDto {
  overallStatus: 'UP' | 'DEGRADED' | 'DOWN';
  providers: ProviderStatusDto[];
}

export interface AlertDto {
  severity: 'critical' | 'warning';
  title: string;
  detail: string;
}

// --- Integrations (AdminIntegrationsController / com.finora.service.AdminIntegrationsService) ---
// IntegrationDto reuses the SAME live status ProviderStatusDto carries, plus a curated
// description -- see the backend DTO's own comment for why Database/Financial Intelligence
// Engine/Statement Import are internal engine checks, not integrations, and stay off this page.

interface IntegrationDto {
  name: string;
  category: string;
  description: string;
  status: 'UP' | 'DEGRADED' | 'DOWN';
  detail: string;
}

/** No status field: nothing is running yet for these, so there is nothing to check. */
interface UpcomingIntegrationDto {
  name: string;
  description: string;
}

export interface IntegrationsOverviewDto {
  integrations: IntegrationDto[];
  upcoming: UpcomingIntegrationDto[];
}

/** Every field here is a real, currently-unaddressed platform-wide count -- see backend
 *  NeedsAttentionDto's own doc comment. Not a fabricated to-do list. */
export interface NeedsAttentionDto {
  importsWithSkippedRowsToday: number;
  lockedAccounts: number;
  transactionsNeedingCategoryReview: number;
  transactionsFlaggedAsDuplicates: number;
}

/** Mirrors backend OperationalDashboardDto exactly. importsWithSkippedRowsToday is the honest
 *  substitute for "failed imports" -- see that record's own doc comment for why this pipeline
 *  has no real FAILED signal to report today. inactiveUsersLast7Days is the inverse of
 *  activeUsersToday's own query -- a user who predates the 7-day window with no login in it, or
 *  none ever -- an Insights figure, not a daily-reset tile, so it has no previousDay sibling. */
export interface OperationalDashboardDto {
  totalUsers: number;
  activeUsersToday: number;
  transactionsToday: number;
  importsToday: number;
  importsWithSkippedRowsToday: number;
  inactiveUsersLast7Days: number;
  previousDay: PreviousDayDto;
  needsAttention: NeedsAttentionDto;
  health: PlatformHealthDto;
  alerts: AlertDto[];
  recentActivity: AuditLogDto[];
}

/** Mirrors backend PreviousDayDto exactly -- yesterday's counts for the four stat tiles that
 *  reset daily, backing each tile's "vs yesterday" delta. No totalUsers sibling: see that
 *  record's own doc comment for why a running total has no "vs yesterday" comparison. */
interface PreviousDayDto {
  activeUsers: number;
  transactions: number;
  imports: number;
  importsWithSkippedRows: number;
}

/** D-27 PR3-D. Mirrors backend ActivationFunnelDto exactly -- see that record's own doc comment
 *  for what "reached" means (ever, not currently-active) and why stages aren't guaranteed to be
 *  strict subsets of each other. */
export interface ActivationFunnelDto {
  signedUp: number;
  firstImport: number;
  firstBudget: number;
  firstGoal: number;
}

/** Mirrors backend ActivityTrendPointDto exactly -- one calendar day of the Platform Activity
 *  chart, oldest first, today included. date is a plain calendar day (YYYY-MM-DD), not a
 *  timestamp -- there is no time-of-day component to a daily point. */
export interface ActivityTrendPointDto {
  date: string;
  signups: number;
  imports: number;
  transactions: number;
}

/** D-28 PR4-A. Mirrors backend BillingDtos.SubscriptionSummaryDto exactly -- one row per user's
 *  current subscription, joined with their plan and account details for the admin list. */
/** D-28 PR4-C. Mirrors backend ReferralDtos.AdminReferralSummaryDto exactly -- one row per
 *  referral, both parties identified for abuse review. */
export interface AdminReferralSummaryDto {
  referralId: string;
  referrerUserId: string;
  referrerEmail: string | null;
  referrerFullName: string | null;
  referredUserId: string;
  referredEmail: string | null;
  referredFullName: string | null;
  status: string;
  reward: number | null;
  createdAt: string;
}

export interface SubscriptionSummaryDto {
  subscriptionId: string;
  userId: string;
  userEmail: string | null;
  userFullName: string | null;
  planCode: string | null;
  planName: string | null;
  status: string;
  startDate: string;
  endDate: string | null;
  renewalDate: string | null;
}

export interface SystemHealthDto {
  status: string;
  components: Record<string, string>;
  uptimeSeconds: number;
  checkedAt: string;
}

/** Mirrors backend RecentImportDto exactly -- Admin Portal Phase 7's closest honest equivalent to
 *  a background-job monitor (see that record's own doc comment for why a statement_imports row
 *  can only ever be a completed import, and hadSkippedRows is the one real per-row signal worth
 *  showing). */
export interface RecentImportDto {
  id: string;
  userId: string;
  userEmail: string;
  fileName: string;
  transactionsImported: number;
  transactionsSkipped: number;
  hadSkippedRows: boolean;
  importedAt: string;
}

export interface MeAccessDto {
  roles: string[];
  permissions: string[];
}

/** Mirrors backend DiagnosticsDto -- see its own class doc for the full reasoning. version/
 *  gitCommit are null when the app was started without the build-info/git-commit-id Maven goals
 *  having run (e.g. an IDE run configuration), never a fabricated placeholder. */
interface ApplicationInfoDto {
  version: string | null;
  gitCommit: string | null;
  springProfile: string;
}

interface RuntimeInfoDto {
  uptimeSeconds: number;
  flywayVersion: string;
  cacheEnabled: boolean;
}

/** phoneVerificationPolicy is a fixed descriptive string, not a toggle -- see ADR-0001. */
interface ConfigurationSummaryDto {
  registrationsEnabled: boolean;
  setupCompleted: boolean;
  phoneVerificationPolicy: string;
}

export interface PlatformDiagnosticsDto {
  application: ApplicationInfoDto;
  runtime: RuntimeInfoDto;
  health: PlatformHealthDto;
  configuration: ConfigurationSummaryDto;
  recentImports: RecentImportDto[];
}

export interface PermissionDto {
  id: string;
  name: string;
  description: string;
}

export interface RoleDto {
  id: string;
  name: string;
  description: string;
  permissions: PermissionDto[];
}

export interface AuditLogDto {
  id: string;
  userId: string;
  action: string;
  entityType: string;
  entityId: string | null;
  metadata: Record<string, unknown> | null;
  requestId: string | null;
  createdAt: string;
}

// --- Bank registry admin management (BankManagementService / AdminBankController) ---

export interface BankDto {
  id: string;
  officialName: string;
  shortName: string;
  colorHex: string;
  initials: string;
  logoPath: string;
  category: string | null;
  websiteUrl: string | null;
  ifscPrefix: string | null;
  supportedAccountTypes: string[];
}

export interface CreateBankRequest {
  id: string;
  officialName: string;
  shortName: string;
  colorHex?: string;
  initials?: string;
  category?: string;
  websiteUrl?: string;
  ifscPrefix?: string;
}

export interface UpdateBankRequest {
  officialName?: string;
  shortName?: string;
  colorHex?: string;
  initials?: string;
  category?: string;
  websiteUrl?: string;
  ifscPrefix?: string;
}

// --- Real platform-wide configuration (PlatformSettingsService / PlatformSettingsController) ---

export interface PlatformSettingsDto {
  registrationsEnabled: boolean;
  maxFailedLoginAttempts: number;
  lockoutDurationMinutes: number;
  updatedAt: string;
}

export interface UpdatePlatformSettingsRequest {
  registrationsEnabled?: boolean;
  maxFailedLoginAttempts?: number;
  lockoutDurationMinutes?: number;
}

// --- Act-on-behalf-of-user: accounts + transactions (AdminAccountController / AdminTransactionController) ---

export interface AccountDto {
  id: string;
  name: string;
  accountType: string;
  balance: number;
  creditLimit: number | null;
  dueDate: string | null;
  investmentKind: string | null;
  accountHolderName: string | null;
  accountNumberMasked: string | null;
  branchName: string | null;
  ifscCode: string | null;
  bank: BankDto;
  lastImportedAt: string | null;
  lastStatementPeriodStart: string | null;
  lastStatementPeriodEnd: string | null;
  statementsCount: number;
  transactionsCount: number;
  status: string;
}

// Phase 1 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md.
// coverageStatus is a display convenience only -- render off the boolean flags, which are the
// authoritative contract (that document's §0.24).
export interface CoverageSegmentDto {
  statementImportId: string;
  periodStart: string;
  periodEnd: string;
  classification: 'STANDARD' | 'NON_STANDARD_PERIOD';
}

export interface CoverageGapDto {
  gapStart: string;
  gapEnd: string;
  daysMissing: number;
  delta: number | null;
}

export interface CoverageOverlapDto {
  segmentAId: string;
  segmentBId: string;
  overlapStart: string;
  overlapEnd: string;
  type: 'EXACT_DUPLICATE' | 'PARTIAL';
}

export interface CoverageDto {
  accountId: string;
  coverageStatus: string;
  coveredDays: number;
  missingDays: number;
  coveragePercentage: number | null;
  hasGaps: boolean;
  hasOverlaps: boolean;
  hasNonStandardPeriods: boolean;
  hasDuplicatePeriods: boolean;
  segments: CoverageSegmentDto[];
  gaps: CoverageGapDto[];
  overlaps: CoverageOverlapDto[];
}

export interface CreateAccountRequest {
  name: string;
  accountType: string;
  balance: number;
  creditLimit?: number | null;
  dueDate?: string | null;
  investmentKind?: string | null;
  accountHolderName?: string | null;
  accountNumberMasked?: string | null;
  bankId: string;
  branchName?: string | null;
  ifscCode?: string | null;
}

export interface TransactionDto {
  id: string;
  accountId: string;
  categoryId: string | null;
  categoryName: string | null;
  date: string;
  description: string;
  merchant: string | null;
  paymentMethod: string | null;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  tags: string[];
  notes: string | null;
  reconciliationStatus: string;
  recurring: boolean;
  needsCategoryReview: boolean;
  categoryManuallySet: boolean;
}

// --- Support-assisted signup / profile edit (AdminUserService) ---

export interface CreateUserRequest {
  email: string;
  password: string;
  fullName: string;
  phoneNumber: string;
}

export interface AdminUpdateUserRequest {
  fullName?: string;
  phoneNumber?: string;
  lowBalanceThreshold?: number;
  timezone?: string;
}

// --- Global category rule admin management (RuleService.listGlobal/... / AdminRuleController) ---

export interface RuleDto {
  id: string;
  scope: 'GLOBAL' | 'USER';
  field: string;
  operator: string;
  comparisonValue: string;
  actionType: string;
  actionValue: string | null;
  priority: number;
  enabled: boolean;
  matchCount: number;
  lastMatchedAt: string | null;
}

export interface CreateRuleRequest {
  field: string;
  operator: string;
  comparisonValue: string;
  actionType: string;
  actionValue?: string;
  priority?: number;
}

export interface UpdateRuleRequest {
  field?: string;
  operator?: string;
  comparisonValue?: string;
  actionType?: string;
  actionValue?: string;
  priority?: number;
  enabled?: boolean;
}

// --- Merchant Intelligence (AdminMerchantStatsService / AdminUserMerchantController) ---

/** One row in the platform-wide catalog -- see the backend's MerchantRepository
 *  .platformMerchantCounts() doc comment for exactly what userCount/rowCount mean. */
export interface MerchantStatDto {
  canonicalName: string;
  userCount: number;
  rowCount: number;
}

/** One row in the Gmail parser-health section (C6.2) -- see the backend's
 *  GmailMerchantParserStatDto doc comment for exactly what each count means. successRate is null
 *  when noParserYet is the only traffic this domain has ever produced -- there is no parser to
 *  rate yet, distinct from a parser that regressed to 0%. */
export interface GmailMerchantParserStatDto {
  domain: string;
  merchant: string;
  parsed: number;
  parseFailed: number;
  skippedNotReceipt: number;
  noParserYet: number;
  successRate: number | null;
  lastSeen: string | null;
}

// --- Gmail merchant templates (MerchantTemplateAdminService / AdminMerchantTemplateController) ---
//
// Not the trust boundary -- that's TrustedSenderDomain (gmail_trusted_sender_domains). A template
// for a domain that isn't trusted is simply unreachable; domainIsTrusted below is purely
// informational, so the UI can warn about that rather than an admin wondering why a
// correctly-tested template produces nothing in production.

export interface MerchantTemplateDto {
  id: string;
  merchantDomain: string;
  merchantName: string;
  receiptMarker: string;
  /** Optional. Pipe-separated literal phrases that mean a message is NOT a receipt for this
   *  template (a refund, return, exchange, or cancellation notice), checked before receiptMarker
   *  -- see MerchantTemplate.matchesNonReceiptMarker's own doc comment. */
  nonReceiptMarker: string | null;
  amountPattern: string;
  datePattern: string;
  enabled: boolean;
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
  domainIsTrusted: boolean;
}

export interface CreateMerchantTemplateRequest {
  merchantDomain: string;
  merchantName: string;
  receiptMarker: string;
  nonReceiptMarker: string;
  amountPattern: string;
  datePattern: string;
}

/** Domain is deliberately not included -- it is immutable after creation, same reasoning as
 *  TrustedSenderDomain's own rename-only-the-label design. */
export interface UpdateMerchantTemplateRequest {
  merchantName: string;
  receiptMarker: string;
  nonReceiptMarker: string;
  amountPattern: string;
  datePattern: string;
}

/** Dry-run against a pasted sample email -- never creates or persists a template. See
 *  AdminMerchantTemplateController's /test endpoint doc comment. merchantDomain is carried
 *  through into the result for display only; it does not affect matching. */
export interface TestMerchantTemplateRequest {
  merchantDomain: string;
  receiptMarker: string;
  nonReceiptMarker: string;
  amountPattern: string;
  datePattern: string;
  sampleHtml: string;
}

export interface TestMerchantTemplateResult {
  status: 'PARSED' | 'NOT_A_RECEIPT' | 'MALFORMED';
  reason: string | null;
  amount: number | null;
  transactionDate: string | null;
  confidence: number | null;
  violations: { field: string; reason: string }[];
}

interface MerchantDistributionEntry {
  category: string;
  confirmationCount: number;
  confidence: number;
}

/** A single user's merchant, as returned by both the self-service and admin-proxy endpoints --
 *  same shape either way (AdminUserMerchantController reuses MerchantService directly). */
export interface MerchantDto {
  id: string;
  canonicalName: string;
  logoUrl: string | null;
  website: string | null;
  topCategory: string | null;
  topCategoryConfidence: number | null;
  distribution: MerchantDistributionEntry[];
}

export interface MerchantUpdateRequest {
  canonicalName?: string;
  website?: string;
}

export interface MerchantMergeRequest {
  mergeFromMerchantId: string;
}

// --- Rule Engine module: dry-run testing (AdminRuleController POST /admin/rules/test) ---

export interface TestRuleRequest {
  field: string;
  operator: string;
  comparisonValue: string;
  sampleDescription?: string;
  sampleAmount?: number;
  sampleMerchant?: string;
  sampleAccountType?: string;
}

export interface TestRuleResult {
  matches: boolean;
}

// --- Learning Engine (AdminLearningStatsController / AdminUserLearningController) ---

export interface LearningTimelineEntry {
  id: string;
  merchantId: string;
  merchantName: string;
  action: string;
  previousCategoryName: string | null;
  newCategoryName: string | null;
  createdAt: string;
}

export interface LearningSummaryDto {
  learnedMerchants: number;
  totalConfirmations: number;
  correctedCount: number;
  resetCount: number;
}

// --- Relationship Engine (AdminUserRelationshipController) ---

interface RelationshipIdentifierDto {
  id: string;
  identifierType: string;
  identifierValue: string;
}

export interface RelationshipDto {
  id: string;
  label: string;
  relationshipType: string;
  linkedAccountId: string | null;
  identifiers: RelationshipIdentifierDto[];
}

interface RelationshipIdentifierRequest {
  identifierType: string;
  identifierValue: string;
}

export interface CreateRelationshipRequest {
  label: string;
  relationshipType: string;
  linkedAccountId?: string;
  identifiers: RelationshipIdentifierRequest[];
}

/** Every field optional -- only supplied ones change. identifiers, when supplied, REPLACES the
 *  relationship's whole identifier list rather than appending; see the backend's
 *  RelationshipDto.UpdateRequest for why a full replace is the one unambiguous contract. */
export interface UpdateRelationshipRequest {
  label?: string;
  relationshipType?: string;
  linkedAccountId?: string;
  identifiers?: RelationshipIdentifierRequest[];
}

export interface RelationshipMergeRequest {
  mergeFromRelationshipId: string;
}

// --- Per-user analytics (AdminUserAnalyticsController) ---

export interface TopMerchantPoint {
  merchantId: string;
  merchantName: string;
  totalSpend: number;
  transactionCount: number;
}

export interface TrendPoint {
  month: string;
  totalSpend: number;
}

export interface CategoryConfidencePoint {
  category: string;
  avgConfidence: number;
  merchantCount: number;
}

export interface TopCategoryPoint {
  categoryId: string;
  categoryName: string;
  totalSpend: number;
  transactionCount: number;
}

export interface LearningGrowthPoint {
  month: string;
  learnedCount: number;
  correctedCount: number;
}

export interface LearningPlatformStatsDto {
  learnedMerchantPairs: number;
  totalConfirmations: number;
  correctedCount: number;
  resetCount: number;
  trend: LearningGrowthPoint[];
}

// --- Reconciliation Monitor (AdminReconciliationStatsController / AdminUserWorkspaceController) ---

export interface ReconciliationStatsDto {
  okCount: number;
  duplicateCount: number;
  transferCount: number;
  refundCount: number;
  reversalCount: number;
  investmentTransferCount: number;
  supersededCount: number;
  recurringCount: number;
  totalTransactions: number;
}

/* ── Reconciliation Explorer ───────────────────────────────────────────────────────────────────
 * One transaction, raw through to final classification (AdminReconciliationExplorerController).
 * Assembled, not scored -- same position ImportTrace takes: each block reports what its own
 * table recorded, no derived verdict. Mirrors backend ReconciliationExplorerDto exactly.
 */

export interface ReconciliationExplorerRaw {
  transactionId: string;
  description: string | null;
  amount: number;
  txnType: 'INCOME' | 'EXPENSE';
  txnDate: string;
  source: 'MANUAL' | 'CSV_IMPORT' | 'GMAIL_IMPORT';
}

/** categoryName is null for an uncategorized transaction, not a lookup failure. */
export interface ReconciliationExplorerNormalized {
  merchant: string | null;
  categoryName: string | null;
}

/** confidence and sourceTrust are 0-100, not the 0.0-1.0 scale the roadmap doc's own design
 *  example used -- one confidence-scale convention across the codebase (see ConfidenceScorer's
 *  own javadoc), matching decisionConfidence elsewhere. Both are null for an edge written before
 *  the confidence engine shipped. */
export interface ReconciliationExplorerEdge {
  edgeId: string;
  counterpartTransactionId: string;
  relationshipType: 'TRANSFER' | 'REFUND' | 'REVERSAL' | 'DUPLICATE' | 'CC_PAYMENT' | 'EMI'
    | 'SALARY' | 'LOAN_REPAYMENT' | 'INVESTMENT_TRANSFER' | 'CASH_WITHDRAWAL' | 'CASH_DEPOSIT';
  confidence: number | null;
  sourceTrust: number | null;
  status: 'CANDIDATE' | 'AUTO_CONFIRMED' | 'USER_CONFIRMED' | 'REJECTED';
  detectionMethod: 'RULE_ENGINE' | 'MANUAL' | 'AA_FEED' | 'USER_OVERRIDE';
  explanation: Record<string, unknown> | null;
}

export interface ReconciliationExplorerClassification {
  reconciliationStatus: 'OK' | 'DUPLICATE' | 'TRANSFER' | 'REFUND' | 'REVERSAL';
  /** Null means classified before this existed, or never matched -- not a failure state. */
  transactionExplanation: Record<string, unknown> | null;
}

export interface ReconciliationExplorerTrace {
  raw: ReconciliationExplorerRaw;
  normalized: ReconciliationExplorerNormalized;
  /** Depth-1 edges touching this transaction directly -- empty means unmatched, not "not
   *  looked up". */
  edges: ReconciliationExplorerEdge[];
  classification: ReconciliationExplorerClassification;
}

/* ── Import Row Trace ──────────────────────────────────────────────────────────────────────────
 * One import, row by row (AdminImportRowTraceController) -- scoped to successfully-imported rows
 * only; a dropped or excluded-by-user row stays aggregate-only, same as ImportTrace's existing
 * verification findings. Mirrors backend ImportRowTraceDto exactly.
 */

export interface ImportRowOutcome {
  rowPosition: number;
  transactionId: string;
  description: string | null;
  amount: number;
  txnDate: string;
}

/** rows is empty (not missing) when this import predates row-position tracking, or was confirmed
 *  by a client that predates echoing it -- "no position data available" is a real, statable
 *  answer, not an error. */
export interface ImportRowTrace {
  statementImportId: string;
  rows: ImportRowOutcome[];
}

/* ── Insight Explorer ──────────────────────────────────────────────────────────────────────────
 * One user's dashboard insights, traced back to the transaction set and formula that produced
 * each number (AdminInsightsExplorerController). Mirrors backend InsightsExplorerDto exactly.
 */

/** rawAmount and reportableAmount differ when a refund was netted off this expense -- the gap
 *  between the two IS the trace for that transaction. */
export interface InsightsExplorerTracedTransaction {
  transactionId: string;
  description: string | null;
  rawAmount: number;
  reportableAmount: number;
  txnDate: string;
}

export interface InsightsExplorerTotalSpend {
  amount: number;
  categoryCount: number;
  transactions: InsightsExplorerTracedTransaction[];
}

export interface InsightsExplorerTopCategory {
  category: string;
  amount: number;
  transactions: InsightsExplorerTracedTransaction[];
}

export interface InsightsExplorerTopMerchant {
  merchant: string;
  amount: number;
  transactions: InsightsExplorerTracedTransaction[];
}

/** reportingMonth and every number are null when the user has no reportable expense
 *  transactions at all -- the same state the user-facing dashboard answers with its own
 *  "upload or add transactions" sentence, not a lookup failure. */
export interface InsightsExplorerTrace {
  userId: string;
  reportingMonth: string | null;
  reportingMonthIsCurrent: boolean;
  totalSpend: InsightsExplorerTotalSpend | null;
  topCategory: InsightsExplorerTopCategory | null;
  topMerchant: InsightsExplorerTopMerchant | null;
}

interface WorkspaceHealthDto {
  rulesEnabled: boolean;
  merchantLearningActive: boolean;
  reconciliationHealthy: boolean;
  recurringDetectionHealthy: boolean;
  auditLoggingHealthy: boolean;
}

/** Mirrors backend WorkspaceSummaryDto exactly -- see that record's doc comment for what each
 *  field does and doesn't mean (categorizationAccuracy is an automation rate, not a correctness
 *  measure; transferMatches counts 2 per real transfer event, not 1). */
export interface WorkspaceSummaryDto {
  totalTransactions: number;
  totalAccounts: number;
  totalMerchants: number;
  learnedMerchants: number;
  activeRules: number;
  relationships: number;
  statementsImported: number;
  categorizationAccuracy: number | null;
  confidenceDistribution: Record<string, number>;
  duplicateMatches: number;
  transferMatches: number;
  refundMatches: number;
  recurringTransactions: number;
  recentActivity: AuditLogDto[];
  health: WorkspaceHealthDto;
}

// --- Platform Analytics (AdminPlatformAnalyticsController) ---

export interface PlatformCategorySpendDto {
  categoryName: string;
  totalSpend: number;
  transactionCount: number;
}

export interface PlatformMerchantSpendDto {
  merchantName: string;
  totalSpend: number;
  transactionCount: number;
}

export interface PlatformAnalyticsDto {
  topCategories: PlatformCategorySpendDto[];
  topMerchants: PlatformMerchantSpendDto[];
}

// --- Global Search (AdminSearchController) ---

/** Mirrors backend SearchResultDto exactly. type is one of 'user' | 'merchant' | 'bank' | 'rule'
 *  today -- kept as a plain string rather than a union so a future backend-added type shows up
 *  (with a generic fallback icon) instead of failing to typecheck. link is a frontend-admin route
 *  path, ready to pass straight to react-router's navigate(). */
export interface SearchResultDto {
  type: string;
  id: string;
  title: string;
  subtitle: string;
  link: string;
}

// --- Feature flags (Admin Portal Phase 8, AdminFeatureFlagController) ---

/** Mirrors backend FeatureFlagDto exactly. `key` is the stable identifier real code checks
 *  against (see RecurringService.detectForUser's doc comment for the one call site wired to a
 *  flag today) -- `id` is only the row id, used for the PUT .../{id} toggle call. */
export interface FeatureFlagDto {
  id: string;
  key: string;
  description: string;
  enabled: boolean;
  updatedAt: string;
}

export interface UpdateFeatureFlagRequest {
  enabled: boolean;
}

/* ── Layout Studio ─────────────────────────────────────────────────────────────────────────────
 * The read side of statement_analysis_sessions: one row per upload ATTEMPT, successes and
 * failures alike. Distinct from the layout-intelligence types above, which are derived from
 * statement_imports and therefore only ever describe documents that succeeded.
 *
 * Nothing here identifies a person: no file name, no user id. A document is referred to by its
 * quotable handle (SA-20260806-0145), a layout by its fingerprint. See
 * StatementAnalysisReportService's doc comment for why.
 */

/** Reason -> count. Ordered by count descending, dominant reason first. */
export type UnanchoredReasons = Record<string, number>;

export interface StatementAnalysisDto {
  reference: string;
  sourceFormat: string | null;
  /** Null when the document failed before it could be characterised -- e.g. a wrong password. */
  layoutFingerprint: string | null;
  outcome: 'PARSED' | 'FAILED';
  failureCode: string | null;
  sectionCount: number | null;
  /** Null means never measured. Deliberately NOT the same as 0 -- see the page's RowCount cell. */
  rowCount: number | null;
  unanchoredReasons: UnanchoredReasons;
  unanchoredRowCount: number;
  durationMs: number | null;
  byteSize: number | null;
  createdAt: string;
}

export interface StatementAnalysisDetailDto {
  analysis: StatementAnalysisDto;
  /** Including this one. 0 when the document was never characterised at all. */
  timesLayoutSeen: number;
  timesLayoutFailed: number;
}

/* ── Import trace ──────────────────────────────────────────────────────────────────────────────
 * One import, end to end. Milestone 2's sixth success criterion: an administrator can follow an
 * upload from parsing through verification and learning to completion in a single view, without a
 * log or an engineer.
 *
 * Assembled, not aggregated — there is no health field, no score and no overall verdict. Each block
 * reports what its own table recorded and the reader draws the conclusion, the same position
 * VerificationReport takes about combining rules. Blocks are nullable or empty by design: an
 * asynchronous job records no analysis row, a synchronous import has no job and no stage timings,
 * and a staged-but-unconfirmed import has no completion. A missing block means "this path does not
 * record that", which is a real answer.
 *
 * Nothing here identifies a person: no file name, no user id. The handles are the analysis
 * reference (SA-20260806-0145) and the job id.
 */

interface ImportTraceJob {
  status: string;
  attemptCount: number;
  rowsTotal: number | null;
  rowsProcessed: number;
  lastError: string | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  /** Queued to finished — what the person who uploaded actually waited. Not any one stage's
   *  duration, and not the analysis session's parse time. */
  totalDurationMs: number | null;
}

export interface ImportTraceStage {
  stage: string;
  attempt: number;
  /** A stage still RUNNING on a finished job is a worker that died inside it. SKIPPED is a stage
   *  that did not run at all — the observation that can prove optimising it unnecessary. */
  outcome: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
}

export interface ImportTraceFinding {
  sectionIndex: number;
  rule: string;
  outcome: string;
  /** Structural facts only — counts, booleans and bounded enum constants. Balances and raw cell
   *  values are absent by construction. */
  details: Record<string, unknown>;
  recordedAt: string;
}

interface ImportTraceLearningEvent {
  id: string;
  status: string;
  attemptCount: number;
  createdAt: string;
}

interface ImportTraceLearning {
  /** Zero is a legitimate answer: an import of merchants Finora already knew teaches it nothing. */
  events: number;
  byStatus: Record<string, number>;
  /** The ones that have not completed, bounded. The only ones anyone acts on. */
  outstanding: ImportTraceLearningEvent[];
}

interface ImportTraceCompletion {
  /** Null when nothing was confirmed. Staging successfully and importing are different events —
   *  a job reaching COMPLETED means only the first, because confirming is the user's decision. */
  statementImportId: string | null;
  transactionsImported: number | null;
  transactionsSkipped: number | null;
  importedAt: string | null;
  sessionConfirmedAt: string | null;
}

/** One of a customer's own recent failed imports, looked up by email -- the support half of
 *  Premium Import Reliability v1, §4 (`GET /admin/imports/analyses/failures/by-user`). Deliberately
 *  narrower than {@link ImportTrace}: file name is included because a support conversation starts
 *  from "this customer's file", but nothing else PII-shaped rides along -- same boundary as the
 *  customer's own `GET /import/failures`. */
export interface CustomerFailureSummary {
  reference: string;
  fileName: string;
  failureCode: string | null;
  createdAt: string;
}

export interface ImportTrace {
  analysisReference: string | null;
  importJobId: string | null;
  importSessionId: string | null;
  correlationId: string | null;
  analysis: StatementAnalysisDetailDto | null;
  job: ImportTraceJob | null;
  stages: ImportTraceStage[];
  /** Empty means no verification was recorded — which is not the same as every rule passing. */
  verification: ImportTraceFinding[];
  learning: ImportTraceLearning;
  completion: ImportTraceCompletion;
}

export interface StatementAnalysisSummaryDto {
  analysesInWindow: number;
  totalAnalysesEver: number;
  parsed: number;
  failed: number;
  distinctLayouts: number;
  rowsExtractedInWindow: number;
  unanchoredRowsInWindow: number;
  unanchoredReasons: UnanchoredReasons;
}

/**
 * One row of the merchant learning queue (WI2).
 *
 * Mirrors the backend's LearningQueueDto. The name/id pairs are deliberate: an operator must be
 * able to answer "who was affected" and "which statement produced this" without leaving the page,
 * and an id alone sends them to a database client -- the exact outcome this surface exists to
 * prevent. Names are nullable because the row they came from may since have been deleted, and an
 * event whose merchant is gone is precisely the kind most likely to be stuck.
 */
export interface LearningQueueEvent {
  id: string;
  status: 'PENDING' | 'PROCESSING' | 'FAILED' | 'COMPLETED' | 'RESOLVED';
  attemptCount: number;
  maxAttempts: number;
  /** Computed server-side, so the UI cannot drift from the backend's state machine and offer a
   *  Retry the API would then refuse. */
  retryable: boolean;
  nextAttemptAt: string | null;
  lastError: string | null;
  firstFailedAt: string | null;
  lastRetryAt: string | null;
  createdAt: string;
  userId: string;
  userEmail: string | null;
  merchantId: string;
  merchantName: string | null;
  categoryId: string;
  categoryName: string | null;
  statementImportId: string | null;
  statementFileName: string | null;
  /** Null when the import never had a staging session -- never a placeholder. */
  importSessionId: string | null;
}

export interface LearningQueueSummary {
  pending: number;
  processing: number;
  failed: number;
  completed: number;
  resolved: number;
}

/**
 * One row of the admin notification dashboard (Task 12). Mirrors the backend's
 * NotificationAdminDto.
 *
 * Deliberately has no email/phone field, unlike LearningQueueEvent's userEmail -- userId is a
 * bare UUID with no join back to the user's contact details, matching
 * AdminNotificationController's own no-PII-exposure requirement. There is also no `retryable` or
 * any action flag: this dashboard is read-only, there is nothing here for the UI to offer.
 */
export interface NotificationAdminRow {
  id: string;
  userId: string;
  type: string;
  category: string;
  channel: 'EMAIL' | 'SMS' | 'PUSH';
  priority: string;
  status: 'CREATED' | 'QUEUED' | 'PROCESSING' | 'SENT' | 'RETRYING' | 'DEAD_LETTER';
  title: string;
  attemptCount: number;
  nextAttemptAt: string | null;
  lastError: string | null;
  sentAt: string | null;
  createdAt: string;
}

/**
 * One row of the held-imports triage queue.
 *
 * Carries no `lastError`, deliberately -- see HeldImportDto's own doc on the backend. That field is
 * a raw parser message, and a parser message routinely quotes the statement content that defeated
 * it. The list view is a page an operator leaves open; it gets the curated `failureCode` only.
 * `userId` is a bare id for the same reason NotificationAdminRow's is: the controller never joins
 * to a user's contact details, so no email or phone can reach this screen.
 */
export interface HeldImportRow {
  id: string;
  userId: string;
  fileName: string;
  sourceFormat: string | null;
  failureCode: string | null;
  attemptCount: number;
  recoveryCount: number;
  createdAt: string;
  heldAt: string | null;
}

/** A held import plus the diagnostics an engineer needs. Every fetch of this is audited. */
export interface HeldImportDetail {
  job: HeldImportRow;
  lastError: string | null;
  correlationId: string | null;
  objectKey: string | null;
}

/** `reprocessing` counts jobs already sent back to the queue, not every queued import. */
export interface HeldImportSummary {
  held: number;
  reprocessing: number;
}

/** The trust-review lifecycle, mirroring `HeldStatement.Status` on the backend. */
export type HeldStatementStatus =
  | 'HELD' | 'ASSIGNED' | 'INVESTIGATING' | 'READY_FOR_IMPORT' | 'IMPORTED' | 'REJECTED';

/**
 * One row of the held-statement (trust-review) queue.
 *
 * Carries no statement content and no object key -- opening the document is a separate, audited
 * endpoint. `userId` is a bare id, same reason `HeldImportRow.userId` is: no email or phone can
 * reach this screen even indirectly. `bankName` is a snapshot from hold time and can be null when
 * the parser could not name a bank.
 */
export interface HeldStatementRow {
  id: string;
  heldId: string;
  importJobId: string;
  userId: string;
  bankName: string | null;
  status: HeldStatementStatus;
  triggerSummary: string | null;
  reliabilityStatus: string | null;
  textSource: string | null;
  headerReconstructionUncertain: boolean | null;
  parserVersion: string | null;
  assignedEngineerId: string | null;
  engineerNotes: string | null;
  rootCause: string | null;
  fixReference: string | null;
  falsePositive: boolean | null;
  createdAt: string;
  assignedAt: string | null;
  readyAt: string | null;
  resolvedAt: string | null;
}

/** Mirrors the backend's `HeldStatementTelemetryDto` exactly, field for field. `falsePositives`
 *  is a count of `approved`, not `resolved` -- see that field's own backend doc for why dividing
 *  by `resolved` would understate the true proportion. */
export interface HeldStatementTelemetrySummary {
  totalHolds: number;
  resolved: number;
  approved: number;
  rejected: number;
  falsePositives: number;
  byCategory: Record<string, number>;
  medianResolutionHours: number | null;
}

/** Every filter is optional; `status` narrows within the open queue and can never surface a
 *  resolved hold -- see the backend's `HeldStatementFilter` for why. */
export interface HeldStatementQuery {
  page?: number;
  size?: number;
  status?: HeldStatementStatus;
  bank?: string;
  olderThanHours?: number;
  engineerId?: string;
}

/** One verification rule's outcome for one section -- the printed-versus-parsed numbers behind
 *  the trigger, not a sentence summarising them. */
export interface HeldStatementFinding {
  sectionIndex: number;
  rule: string;
  outcome: string;
  details: Record<string, unknown>;
  createdAt: string;
}

/** One entry in a hold's audit history. `actorId` null means the system acted. */
export interface HeldStatementEvent {
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  notes: string | null;
  actorId: string | null;
  createdAt: string;
}

/** The summary plus the evidence behind `triggerSummary` and the hold's own history. Still no
 *  statement content -- opening the document is `/document`, gated and audited separately. */
export interface HeldStatementDetail {
  summary: HeldStatementRow;
  findings: HeldStatementFinding[];
  timeline: HeldStatementEvent[];
}

/** What one parser re-run found -- mirrors the backend's `HeldStatementRerunResultDto` exactly. */
export interface HeldStatementRerunResult {
  previousParserVersion: string | null;
  currentParserVersion: string | null;
  parserVersionChanged: boolean;
  stillHeld: boolean;
  reasons: string[];
  summary: HeldStatementRow;
}

/** NotificationAdminRow plus the message body and the provider attempt log, newest first. */
export interface NotificationAdminDetail extends NotificationAdminRow {
  message: string;
  attempts: NotificationAttempt[];
}

export interface NotificationAttempt {
  id: string;
  provider: string;
  response: string | null;
  success: boolean;
  attempt: number;
  timestamp: string;
}

export interface NotificationAdminChannelSummary {
  channel: string;
  sent: number;
  failed: number;
}

/** The dashboard's stat tiles: sent/failed counts, overall and by channel. Deliberately just
 *  counts -- no trend charts, no engagement scoring (proposal section 2.5/4). */
export interface NotificationAdminSummary {
  sent: number;
  failed: number;
  byChannel: NotificationAdminChannelSummary[];
}


/**
 * A merchant the normalization engine invented, awaiting a human decision (WI4).
 *
 * No cross-user fields by design: merchants.user_id is NOT NULL, so a merchant belongs to exactly
 * one person and this milestone introduces no canonical registry. `transactionCount` is the field
 * that decides the action -- 0 means the guess was never real and can be discarded, anything above
 * means it is on the user's ledger and must be merged instead.
 */
export interface MerchantReviewItem {
  id: string;
  userId: string;
  userEmail: string | null;
  canonicalName: string;
  lifecycleStatus: 'TEMPORARY' | 'UNDER_REVIEW' | 'APPROVED';
  transactionCount: number;
  createdAt: string;
}

/**
 * Layout Intelligence — the read side of the layout fingerprint every import has been recording
 * since V39 (docs/engineering/layout-intelligence-proposal.md).
 *
 * Anonymised by construction. Every type below is keyed by fingerprint and carries counts,
 * durations and header names only — no user, account, transaction, bank, file name or balance
 * reaches these shapes. That is what makes platform-wide aggregation operational telemetry rather
 * than cross-user learning, and it is a property of the records themselves, so a new field cannot
 * quietly reintroduce a customer identifier.
 */
export interface LayoutSummary {
  fingerprint: string;
  sourceFormat: string;
  columns: number;
  usageCount: number;
  firstSeen: string;
  lastSeen: string;
  /** Fired on EVERY import of this layout — the stable core. */
  stableCapabilities: string[];
  /** Fired on some imports but not others. Either the documents genuinely differ or the layout is
   *  drifting; this is the set worth looking at. */
  unstableCapabilities: string[];
  unknownHeaders: string[];
  /** Null when no import of this layout has a recorded duration (everything before V53). */
  medianDurationMs: number | null;
  totalRowsImported: number;
  totalRowsSkipped: number;
}

export interface UnknownHeaderSummary {
  header: string;
  importCount: number;
  /** Distinct layouts containing it. Greater than one is the strong signal: a header spanning
   *  several layouts is a gap in the parser's hint lists, not one bank's quirk. */
  layoutCount: number;
  fingerprints: string[];
  firstSeen: string;
  lastSeen: string;
}

export interface LayoutTimelinePoint {
  importedAt: string;
  capabilities: string[];
  unknownHeaders: string[];
  durationMs: number | null;
  rowsImported: number;
  rowsSkipped: number;
  /** This import's capability or unknown-header set differs from the one before it. Says something
   *  changed; says nothing about whether it is bad. */
  changedFromPrevious: boolean;
}

/**
 * The report that decides whether layout reuse is ever worth building.
 *
 * Every numeric field is nullable by absence: when there is not enough data to answer, the answer
 * is omitted rather than defaulted to zero. Rendering a missing measurement as "0 ms" would close
 * the question with a number nobody earned, so the UI must show these as "not measured" rather
 * than falling back with `?? 0`.
 */
export interface LayoutEvidenceReport {
  totalImportsAnalysed: number;
  distinctLayouts: number;
  recurringLayouts: number;
  importsOnRecurringLayouts: number;
  medianDurationFirstEncounter: number | null;
  medianDurationRecurring: number | null;
  avgUnknownHeadersFirstEncounter: number | null;
  avgUnknownHeadersRecurring: number | null;
  avgSkippedRowsFirstEncounter: number | null;
  avgSkippedRowsRecurring: number | null;
  /** Plain-language statement of what the numbers do and do not support — including, and most
   *  often, "no evidence for reuse", which is a successful outcome rather than a gap. */
  verdict: string;
}

// Support, Help & Feedback v1, Phase 9 (admin portal). Mirrors the same
// SupportTicket.Category/Status and FeedbackEntry.Type/Context unions the user-facing frontend and
// mobile apps already carry their own copies of (com.finora.entity.SupportTicket,
// com.finora.entity.FeedbackEntry) -- a value added on one side with nothing here to render it is
// the failure mode a compile-time union exists to catch.
export type SupportTicketCategory =
  | 'STATEMENT_IMPORT' | 'CATEGORIZATION' | 'ACCOUNT_LINKING' | 'DATA_ACCURACY' | 'TECHNICAL_ISSUE' | 'OTHER';
export type SupportTicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
export type ClientPlatform = 'WEB' | 'MOBILE_ANDROID' | 'MOBILE_IOS';

/** One row of the ticket queue -- mirrors SupportTicketDto.Summary. */
export interface SupportTicketRow {
  id: string;
  ticketNumber: string;
  userId: string;
  category: SupportTicketCategory;
  subject: string;
  status: SupportTicketStatus;
  claimedByAdminId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Every filter is optional -- same shape as HeldStatementQuery. `q` searches ticket number and
 *  subject -- see the backend's SupportTicketRepository.findForAdmin for exactly what it matches. */
export interface SupportTicketQuery {
  page?: number;
  size?: number;
  status?: SupportTicketStatus;
  category?: SupportTicketCategory;
  q?: string;
}

export interface SupportTicketAttachment {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

/** Mirrors SupportTicketDto.Detail -- the SAME shape the ticket's own owner sees, served by the
 *  same non-admin-rooted endpoint (SupportTicketController.detail's own doc explains why there is
 *  no separate admin detail route). Structurally excludes internal-note content -- there is no
 *  such field here at all, matching the backend DTO, not merely an admin-portal choice to hide it. */
export interface SupportTicketDetail {
  id: string;
  ticketNumber: string;
  userId: string;
  category: SupportTicketCategory;
  subject: string;
  description: string;
  status: SupportTicketStatus;
  source: ClientPlatform;
  appVersion: string | null;
  claimedByAdminId: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  createdAt: string;
  updatedAt: string;
  attachments: SupportTicketAttachment[];
}

/** Mirrors SupportTicketDto.NoteDto -- admin-only, never reachable from the user-facing endpoint. */
export interface SupportTicketNote {
  id: string;
  adminId: string;
  note: string;
  createdAt: string;
}

export type FeedbackType = 'BUG' | 'FEATURE_REQUEST' | 'IMPROVEMENT' | 'GENERAL';
export type FeedbackContext =
  | 'DASHBOARD' | 'TRANSACTIONS' | 'REPORTS' | 'BUDGETS' | 'GOALS' | 'IMPORT_FLOW' | 'ACCOUNTS' | 'SETTINGS' | 'HELP' | 'OTHER';

/** One row of the feedback list -- mirrors FeedbackDto.Summary. */
export interface FeedbackRow {
  id: string;
  userId: string;
  type: FeedbackType;
  context: FeedbackContext;
  source: ClientPlatform;
  message: string;
  createdAt: string;
}

export interface FeedbackQuery {
  page?: number;
  size?: number;
  type?: FeedbackType;
  context?: FeedbackContext;
}

/** Mirrors FeedbackDto.Breakdown -- always unfiltered across the whole table (see the backend
 *  service method's own doc comment for why), independent of whatever FeedbackQuery filter the
 *  list view has active. */
export interface FeedbackBreakdownCount {
  label: string;
  total: number;
}

export interface FeedbackBreakdown {
  total: number;
  byType: FeedbackBreakdownCount[];
  byContext: FeedbackBreakdownCount[];
  bySource: FeedbackBreakdownCount[];
}
