// Mirrors the backend's DTO shapes exactly (com.finora.dto.*) -- one field for one field, same
// names, so there's no silent drift between what the API actually returns and what this app
// assumes it returns.

export interface UserSummaryDto {
  id: string;
  email: string;
  fullName: string;
  phoneNumber: string | null;
  phoneVerified: boolean;
  status: 'ACTIVE' | 'SUSPENDED';
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

export interface PlatformHealthDto {
  overallStatus: 'UP' | 'DEGRADED' | 'DOWN';
  providers: ProviderStatusDto[];
}

export interface AlertDto {
  severity: 'critical' | 'warning';
  title: string;
  detail: string;
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
 *  has no real FAILED signal to report today. */
export interface OperationalDashboardDto {
  totalUsers: number;
  activeUsersToday: number;
  transactionsToday: number;
  importsToday: number;
  importsWithSkippedRowsToday: number;
  needsAttention: NeedsAttentionDto;
  health: PlatformHealthDto;
  alerts: AlertDto[];
  recentActivity: AuditLogDto[];
}

export interface SystemHealthDto {
  status: string;
  components: Record<string, string>;
  uptimeSeconds: number;
  checkedAt: string;
}

/** Mirrors backend RecentImportDto exactly -- Admin Portal Phase 7's closest honest equivalent to
 *  a background-job monitor (see that record's own doc comment for why status is always
 *  "COMPLETED" and hadSkippedRows is the one real per-row signal worth showing). */
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
export interface ApplicationInfoDto {
  version: string | null;
  gitCommit: string | null;
  springProfile: string;
}

export interface RuntimeInfoDto {
  uptimeSeconds: number;
  flywayVersion: string;
  cacheEnabled: boolean;
}

/** phoneVerificationPolicy is a fixed descriptive string, not a toggle -- see ADR-0001. */
export interface ConfigurationSummaryDto {
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

export interface MerchantDistributionEntry {
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

export interface RelationshipIdentifierDto {
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

export interface RelationshipIdentifierRequest {
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
  recurringCount: number;
  totalTransactions: number;
}

export interface WorkspaceHealthDto {
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

export interface ImportTraceJob {
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

export interface ImportTraceLearningEvent {
  id: string;
  status: string;
  attemptCount: number;
  createdAt: string;
}

export interface ImportTraceLearning {
  /** Zero is a legitimate answer: an import of merchants Finora already knew teaches it nothing. */
  events: number;
  byStatus: Record<string, number>;
  /** The ones that have not completed, bounded. The only ones anyone acts on. */
  outstanding: ImportTraceLearningEvent[];
}

export interface ImportTraceCompletion {
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
