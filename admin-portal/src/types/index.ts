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
