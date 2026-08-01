// Everything BankLogo and the bank picker need to render/search a bank, resolved server-side
// from com.finora.util.BankRegistry so the frontend never hardcodes bank metadata.
// officialName is null for the "OTHER" fallback (unrecognized bank) -- BankLogo shows a generic
// icon instead of initials in that case.
//
// logoPath points at a real SVG file that may not exist yet -- see BankLogo.tsx's own comment
// for why (no bundled/licensed logo assets in this build) -- the component tries to load it and
// falls back to the initials badge on error, so this is safe to render unconditionally.
export interface BankInfo {
  id: string;
  officialName: string | null;
  shortName: string;
  colorHex: string;
  initials: string;
  logoPath: string;
  category: 'PUBLIC_SECTOR' | 'PRIVATE' | 'SMALL_FINANCE' | 'FOREIGN' | null;
  websiteUrl: string | null;
  ifscPrefix: string | null;
  supportedAccountTypes: string[];
}

export interface Account {
  id: string;
  name: string;
  accountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT';
  balance: number;
  creditLimit?: number;
  dueDate?: string;
  investmentKind?: string;
  // Both null on most accounts (manually created, or imported from a file without these
  // columns) — see Setup.tsx's masked-number eye-toggle and Import.tsx's detected-field display.
  accountHolderName?: string | null;
  accountNumberMasked?: string | null;
  // Both optional -- entered manually or detected from a statement's own branch/IFSC columns.
  branchName?: string | null;
  ifscCode?: string | null;
  bank: BankInfo;
  // Both null when this account has never had a statement imported into it (manually created).
  lastImportedAt: string | null;
  lastStatementPeriodStart: string | null;
  lastStatementPeriodEnd: string | null;
  // How many statements/transactions this account has on file -- see AccountDto's own comment
  // on the backend for how these are computed without an N+1 query.
  statementsCount: number;
  transactionsCount: number;
  // Always "ACTIVE" today -- there's no archive/close-account feature yet. See AccountDto's own
  // comment on the backend for why this is still a real field rather than assumed client-side.
  status: string;
}

export interface Transaction {
  id: string;
  accountId: string;
  categoryId: string;
  categoryName: string;
  date: string;
  description: string;
  merchant: string;
  paymentMethod: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  tags: string[];
  notes: string | null;
  reconciliationStatus: 'OK' | 'DUPLICATE' | 'TRANSFER' | 'REFUND';
  recurring: boolean;
  needsCategoryReview: boolean;
  // False whenever the category came from the suggestion engine (rule match, learned merchant
  // match, or a low-confidence "Other" default) or a CSV import; true the moment a user
  // explicitly sets/corrects it — see Ledger.tsx's "Auto"/"Manual" badge.
  categoryManuallySet: boolean;
}

export interface DashboardSummary {
  currentBalance: number;
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
  monthlyIncome: number;
  monthlyExpense: number;
  netCashFlow: number;
  savingsRatePct: number;
  incomeDeltaPct: number | null;
  expenseDeltaPct: number | null;
  netDeltaPct: number | null;
  healthScore: number;
  healthLabel: string;
  healthBreakdown: Record<string, number>;
  spendByCategory: Record<string, number>;
  notifications: string[];
}

export interface Budget {
  id: string;
  categoryId: string;
  categoryName: string;
  monthlyLimit: number;
  spentThisMonth: number;
}

export interface Goal {
  id: string;
  name: string;
  targetAmount: number;
  currentAmount: number;
  targetDate?: string;
}

export interface StagedRow {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  suggestedCategory: string;
  // 'user_rule' / 'global_rule' are the new category_rules-table matches (RuleEngineService);
  // 'rule' stays the pre-existing static-keyword-table match (CategoryRules, util package) --
  // see CategorizationService.decisionSourceFor for the full mapping to the persisted enum.
  categorySource: 'learned' | 'rule' | 'user_rule' | 'global_rule' | 'default' | 'file';
  // Set only when categorySource is 'user_rule' or 'global_rule' -- the id of the category_rules
  // row that produced this suggestion. Echoed back unchanged in the confirm request so it lands
  // on Transaction.decisionRuleId (see Import.tsx's confirmImport).
  ruleId: string | null;
  likelyDuplicate: boolean;
}

// Best-effort fields pulled from the statement itself. Every field is nullable and genuinely
// IS null when the file didn't carry enough signal — none of these are guessed to fill gaps.
// suggestedName is the detected bank's official name (or a clean generic fallback) -- never a
// raw filename; bank carries the resolved logo/color metadata alongside it.
export interface DetectedAccountInfo {
  suggestedName: string;
  suggestedAccountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT';
  openingBalance: number | null;
  closingBalance: number | null;
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  accountNumberMasked: string | null;
  creditLimit: number | null;
  paymentDueDate: string | null;
  accountHolderName: string | null;
  branchName: string | null;
  ifscCode: string | null;
  bank: BankInfo;
}

// One detected account section within a single multi-account PDF upload (e.g. an HSBC-style
// "Composite Statement" bundling a savings account and a credit-card account in one file) --
// see ImportDto.StagedAccountSection on the backend. Structurally identical to the single-account
// staging shape, just one per detected account.
export interface StagedAccountSection {
  detectedAccount: DetectedAccountInfo;
  rows: StagedRow[];
  totalParsed: number;
  flaggedDuplicates: number;
}

export interface ImportSummary {
  imported: number;
  skipped: number;
  duplicatesDetected: number;
  transfersIdentified: number;
  newMerchantsLearned: number;
  accountsCreated: string[];
  categoriesAssigned: Record<string, number>;
  warnings: string[];
  // Everything needed to render a "professional import summary" without a second round-trip --
  // see ImportDto.ConfirmResponse's own comment on the backend.
  account: Account | null;
  totalCredits: number;
  totalDebits: number;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  importDurationMs: number;
  source: string;
}

// Statement History — organized by account rather than a flat list of uploaded files, since
// that's how users actually think about their statements (see StatementImportService).
export interface StatementSummary {
  id: string;
  fileName: string;
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  openingBalance: number | null;
  closingBalance: number | null;
  transactionsImported: number;
  transactionsSkipped: number;
  status: string;
  importedAt: string;
  // Financial Intelligence Workspace, Statement Imports module: how many of this import's own
  // transactions are currently flagged ReconciliationStatus.DUPLICATE. Computed on read
  // (StatementImportDto.Summary), not new storage.
  duplicateCount: number;
}

export interface AccountStatementGroup {
  accountId: string;
  accountName: string;
  accountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT' | 'UNKNOWN';
  bank: BankInfo;
  statements: StatementSummary[];
  // True when the account this history belongs to has been deleted — still shown for a 7-day
  // grace period after deletedAt (see StatementImportService.listGroupedByAccount), then this
  // group stops appearing in the response entirely.
  deleted: boolean;
  deletedAt: string | null;
}

export interface ReimportResult {
  staging: {
    rows: StagedRow[];
    totalParsed: number;
    flaggedDuplicates: number;
    detectedAccount: DetectedAccountInfo;
  };
  accountId: string;
  accountName: string;
}

// --- Merchant Management (docs/financial-intelligence-engine-spec.md §5) ---

export interface MerchantDistributionEntry {
  category: string;
  confirmationCount: number;
  confidence: number;
}

export interface Merchant {
  id: string;
  canonicalName: string;
  logoUrl: string | null;
  website: string | null;
  topCategory: string | null;
  topCategoryConfidence: number | null;
  distribution: MerchantDistributionEntry[];
}

export interface MerchantAuditEntry {
  action: 'LEARNED' | 'CORRECTED' | 'UNDONE' | 'MERGED';
  previousCategory: string | null;
  newCategory: string | null;
  createdAt: string;
}

// --- Rule Engine (docs/rule-engine-relationship-engine-eds.md) ---

export interface Rule {
  id: string;
  scope: 'GLOBAL' | 'USER';
  field: 'DESCRIPTION' | 'AMOUNT' | 'MERCHANT' | 'ACCOUNT_TYPE';
  operator: 'CONTAINS' | 'EQUALS' | 'STARTS_WITH' | 'GT' | 'LT' | 'BETWEEN';
  comparisonValue: string;
  actionType: 'ASSIGN_CATEGORY' | 'MARK_TRANSFER' | 'MARK_INVESTMENT' | 'MARK_SUBSCRIPTION' | 'ADD_TAG';
  actionValue: string | null;
  priority: number;
  enabled: boolean;
  // Financial Intelligence Workspace, Rule Management module -- see
  // RuleEngineService.recordMatch's doc comment for exactly when these increment. For a GLOBAL
  // rule this is a count across every user (one shared row), not just the current user's own.
  matchCount: number;
  lastMatchedAt: string | null;
}

// --- Relationship Engine (docs/rule-engine-relationship-engine-eds.md §3.3) ---

export interface RelationshipIdentifierEntry {
  id: string;
  identifierType: 'UPI_ID' | 'ACCOUNT_LAST4' | 'NAME_PATTERN';
  identifierValue: string;
}

export interface Relationship {
  id: string;
  label: string;
  relationshipType: 'FAMILY' | 'FRIEND' | 'OWN_ACCOUNT' | 'OTHER';
  linkedAccountId: string | null;
  identifiers: RelationshipIdentifierEntry[];
}

// --- Financial Intelligence Workspace (docs/team-message-financial-intelligence-workspace-kickoff.md) ---

export interface AuditLogEntry {
  id: string;
  userId: string;
  action: string;
  entityType: string;
  entityId: string | null;
  metadata: Record<string, unknown> | null;
  requestId: string | null;
  createdAt: string;
}

// Financial Intelligence Workspace, System Settings module. Just the one real, persisted field --
// see backend WorkspaceSettingsService's class comment for why the rest of the System Settings
// page is static, unpersisted "coming in a future release" copy with nothing to fetch here.
export interface WorkspaceSettings {
  autoApplyConfidenceThreshold: number;
  updatedAt: string | null;
}
