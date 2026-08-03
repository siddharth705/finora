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

// A row that could NOT be parsed into a StagedRow -- surfaced instead of silently vanishing (see
// docs/engineering/financial-document-intelligence-principles.md's "Never lose information").
// `raw` is exactly what was extracted (whatever columns the source layout produced); `reason` is
// a human-readable explanation of why it didn't survive normalization. Never confirmable into the
// ledger -- purely so the user can see what the engine actually saw and why it was skipped.
export interface UnparseableRow {
  raw: Record<string, string | null>;
  reason: string;
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
  // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
  // best-effort, nullable, never guessed -- only set when the source statement actually carried
  // a recognizable reference/cheque/instrument-ID column or running-balance column. No UI
  // consumes these yet (that's Phase 2); this just stops them from being silently dropped
  // between staging and the ledger.
  referenceNumber: string | null;
  balanceAfter: number | null;
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

  // Financial Product Discovery (backend: com.finora.imports.product). What this section actually
  // IS, as opposed to what to prefill the account-type field with. The two are different questions:
  // suggestedAccountType always has to name something for the form, while detectedProduct is
  // allowed to say 'UNKNOWN' -- which is a successful outcome, not a failure. When
  // productNeedsReview is true the type above is a prefill and nothing more; the user names the
  // product once rather than having one guessed into their net worth.
  detectedProduct: FinancialProductType;
  productConfidence: number;
  productNeedsReview: boolean;
  productEvidence: string[];
}

// Mirrors the backend FinancialProductType enum. FD/RD/PPF/EPF/NPS/mutual fund/demat route to the
// Investments module rather than a separate Deposits one; LOAN/INSURANCE/FOREX_CARD are recognised
// but not modelled yet, so they surface on the review screen instead of creating anything.
export type FinancialProductType =
  | 'SAVINGS' | 'CURRENT' | 'OVERDRAFT' | 'WALLET'
  | 'CREDIT_CARD'
  | 'FIXED_DEPOSIT' | 'RECURRING_DEPOSIT' | 'PPF' | 'EPF' | 'NPS' | 'MUTUAL_FUND' | 'DEMAT'
  | 'LOAN' | 'INSURANCE' | 'FOREX_CARD'
  | 'UNKNOWN';

// One detected account section within a single multi-account PDF upload (e.g. an HSBC-style
// "Composite Statement" bundling a savings account and a credit-card account in one file) --
// see ImportDto.StagedAccountSection on the backend. Structurally identical to the single-account
// staging shape, just one per detected account.
export interface StagedAccountSection {
  detectedAccount: DetectedAccountInfo;
  rows: StagedRow[];
  totalParsed: number;
  flaggedDuplicates: number;
  unparseableRows: UnparseableRow[];
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
    unparseableRows: UnparseableRow[];
  };
  accountId: string;
  accountName: string;
}

// --- Financial Intelligence Workspace (docs/team-message-financial-intelligence-workspace-kickoff.md) ---
//
// Merchant/Rule/Relationship/AuditLogEntry types used to live here, backing the self-service
// Merchants/Rules/Learning Engine/Analytics/Activity pages. Those are admin-only now -- the
// equivalent types live in the admin portal's own types/index.ts, mirroring the same backend DTOs.

// Financial Intelligence Workspace, System Settings module. Just the one real, persisted field --
// see backend WorkspaceSettingsService's class comment for why the rest of the System Settings
// page is static, unpersisted "coming in a future release" copy with nothing to fetch here.
export interface WorkspaceSettings {
  autoApplyConfidenceThreshold: number;
  updatedAt: string | null;
}
