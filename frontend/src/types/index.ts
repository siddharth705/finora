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

  // Deposit attributes -- see DetectedAccountInfo's own note. Populated only for FD/RD imported
  // from a statement; null for every hand-created account and every ledger account.
  principalAmount?: number | null;
  interestRate?: number | null;
  maturityDate?: string | null;
  maturityAmount?: number | null;
  installmentAmount?: number | null;
  installmentsPaid?: number | null;
  installmentsTotal?: number | null;
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
  // D-25 PR3-A: null/empty below healthScoreMinTransactions -- a score computed from too few
  // transactions is a harsh first impression, not a true reading. Check healthScoreAvailable
  // before rendering these, don't infer availability from healthScore being non-null alone.
  healthScore: number | null;
  healthLabel: string | null;
  healthBreakdown: Record<string, number>;
  healthScoreAvailable: boolean;
  healthScoreTransactionCount: number;
  healthScoreMinTransactions: number;
  spendByCategory: Record<string, number>;
  notifications: string[];
  /**
   * Which month the monthly figures above describe ("2026-07"), or null for an account with no
   * transactions. This is the newest month with DATA, not necessarily the current calendar month —
   * see reportingMonthIsCurrent. The budget notifications deliberately use the calendar month
   * instead, so they agree with the Budgets page.
   */
  reportingMonth: string | null;
  reportingMonthIsCurrent: boolean;
  /**
   * True when the user has fewer than limitedHistoryMonthFloor distinct calendar months of
   * transaction data. Trend deltas and the health score above are still real numbers -- neither
   * is hidden -- but both are prone to thin-data artifacts this far below the floor (a near-empty
   * prior-month denominator for the deltas; a health score built from too few comparable months).
   * historyMonthCount/limitedHistoryMonthFloor let the client render "X / N months" without
   * hardcoding the threshold, mirroring healthScoreTransactionCount/healthScoreMinTransactions.
   */
  limitedHistory: boolean;
  historyMonthCount: number;
  limitedHistoryMonthFloor: number;
  statementCount: number;
  accountCount: number;
  /**
   * True when categoryReviewSpendPct of this month's spend -- transactions flagged
   * needsCategoryReview, the same signal the Ledger's "needs review" badge already uses -- is at
   * or above categoryReviewSpendWarningThresholdPct. Deliberately NOT keyed on the category name
   * "Uncategorized" or "Other": "Other" is a real, resolvable category (the categorization
   * engine's fallback when nothing matched), so landing there doesn't necessarily mean a
   * transaction has no useful category -- it means the categorization engine's own confidence
   * check flagged it for a human to look at.
   */
  categoryReviewWarning: boolean;
  categoryReviewSpendPct: number;
  categoryReviewSpendAmount: number;
  categoryReviewTransactionCount: number;
  categoryReviewSpendWarningThresholdPct: number;
  /**
   * Why incomeDeltaPct/expenseDeltaPct/netDeltaPct came back null when a real percentage might be
   * expected -- 'PARTIAL_PRIOR_MONTH' (the prior calendar month is really just the ragged edge of
   * the same continuous statement window the current month came from) or
   * 'TOO_FEW_PRIOR_TRANSACTIONS' (a real, full prior month, but too few of its own transactions to
   * trust as a ratio's denominator). Null whenever the deltas are real numbers, or null for a
   * self-explanatory reason (no prior period at all, or a genuinely zero prior amount) that
   * doesn't need a "Why?" disclosure. All three deltas share one gate, so there's nothing to say
   * per-metric that isn't already said once here.
   */
  comparisonGateReason: 'PARTIAL_PRIOR_MONTH' | 'TOO_FEW_PRIOR_TRANSACTIONS' | null;
  comparisonGateMinTransactions: number;
  /**
   * The categories behind a real (non-null) expenseDeltaPct -- e.g. "Dining ₹8,000 vs ₹5,000
   * (+60%)" instead of leaving "expenses are up 12%" unexplained. Built from the SAME
   * currentMonth/priorMonth comparison expenseDeltaPct itself comes from, not Insights' own
   * rolling 3-month-average movers -- a different prior-period definition that would disagree
   * with the number it's meant to explain. Always empty when expenseDeltaPct is null.
   */
  expenseCategoryMovers: CategoryMover[];
}

export interface CategoryMover {
  category: string;
  currentAmount: number;
  priorAmount: number;
  pctChange: number | null;
}

// D-25 PR3-B/C. `type` is one of ACCOUNT_CREATED/FIRST_IMPORT/FIRST_BUDGET/FIRST_GOAL/
// FIRST_GOAL_ACHIEVED (FinancialJourneyDto's own constants) -- left as `string`, not a union,
// so an unrecognized future value degrades to a generic label instead of a type error.
interface JourneyMilestone {
  type: string;
  completed: boolean;
  completedAt: string | null;
}
export interface FinancialJourney {
  milestones: JourneyMilestone[];
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
  /**
   * The evidence behind `likelyDuplicate`, or null when nothing matched (WI5).
   *
   * `likelyDuplicate` alone was enough to filter a row out and not enough for anyone to decide.
   * This is what the row appears to repeat, so the person can look at both and choose — which is
   * the whole point of duplicate detection being decision support rather than a filter.
   */
  duplicateMatch: DuplicateMatch | null;
  // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
  // best-effort, nullable, never guessed -- only set when the source statement actually carried
  // a recognizable reference/cheque/instrument-ID column or running-balance column. No UI
  // consumes these yet (that's Phase 2); this just stops them from being silently dropped
  // between staging and the ledger.
  referenceNumber: string | null;
  balanceAfter: number | null;
  // 0.0–1.0, null for every CSV/PDF row (a bank statement line has no extraction-reliability
  // estimate to carry). Populated only for a Gmail-derived row -- display only, per
  // ParsedReceipt's own doc comment: nothing may skip review because this number is high.
  confidence: number | null;
  // The canonical merchant name resolved during staging (read-only — staging never creates a new
  // Merchant/MerchantAlias row), or null when nothing matched an existing merchant. See the backend's
  // MerchantNormalizationEngine.resolveReadOnly for what "matched" means.
  merchant: string | null;
  // 1.0 when `merchant` was resolved, null otherwise. Not a rich confidence score in this phase.
  merchantConfidence: number | null;
  // The category decision's confidence percentage (0-100), from the backend's
  // CategorizationService.Suggestion#confidence(). Null when categorySource is 'file' (a fact
  // from the source document, not a guess). Distinct from `confidence` above (Gmail-receipt
  // extraction reliability) and `merchantConfidence` (merchant-identity resolution).
  categoryConfidence: number | null;
}

export interface MerchantGroup {
  merchantId: string;
  merchantName: string;
  transactionIds: string[];
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
  // A credit-card statement's total bill for this cycle -- only set for a PDF credit-card
  // statement whose payment-summary panel was found; null for CSV imports and for any
  // non-credit-card statement. Deliberately not called "amountDue" to stay unambiguous against
  // a transaction's amount, the minimum payment due, or the account's outstanding balance.
  totalAmountDue: number | null;
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

  // Opaque to the client. A one-way hash of institution + this product's own number, computed
  // server-side at staging (the only point the full number exists) so that confirming a statement
  // recognises a deposit already held instead of creating a second one and double-counting it in
  // net worth. Echo it back unchanged on confirm; never display it.
  productIdentityHash: string | null;

  // What makes a deposit a DEPOSIT rather than a name and a balance. All null for a ledger
  // account, and null on a deposit for fields its own type doesn't have (a fixed deposit has no
  // installmentAmount; a recurring deposit's value builds up over the schedule so it has no
  // principalAmount).
  principalAmount: number | null;
  interestRate: number | null;
  maturityDate: string | null;
  maturityAmount: number | null;
  installmentAmount: number | null;
  installmentsPaid: number | null;
  installmentsTotal: number | null;
}

// Mirrors the backend FinancialProductType enum. FD/RD/PPF/EPF/NPS/mutual fund/demat route to the
// Investments module rather than a separate Deposits one; LOAN/INSURANCE/FOREX_CARD are recognised
// but not modelled yet, so they surface on the review screen instead of creating anything.
type FinancialProductType =
  | 'SAVINGS' | 'CURRENT' | 'OVERDRAFT' | 'WALLET'
  | 'CREDIT_CARD'
  | 'FIXED_DEPOSIT' | 'RECURRING_DEPOSIT' | 'PPF' | 'EPF' | 'NPS' | 'MUTUAL_FUND' | 'DEMAT'
  | 'LOAN' | 'INSURANCE' | 'FOREX_CARD'
  | 'UNKNOWN';

// A rule-based (never weighted) reliability status -- see ImportReliabilityStatus on the
// backend for the exact derivation. Mirrors that enum's three values.
type ImportReliabilityStatus = 'CLEAN' | 'REVIEW_RECOMMENDED' | 'NEEDS_ATTENTION';

// Whether an import can be proven faithful to the statement it came from, and on what basis --
// see ImportDto.VerificationReport on the backend, and
// docs/engineering/import-verification-framework.md for the reasoning.
//
// Deliberately has NO document-level status derived from GUESSING at weights. The backend
// removed the original aggregator idea for exactly that reason -- see its own correction note.
// CORRECTED: `reliabilityStatus` below is that aggregator, now built. It does not reintroduce
// the risk the paragraph above described, because it is a deterministic OR over facts already
// on this report (a finding's own outcome, `headerReconstructionUncertain`, `textSource`), never
// a synthesized score -- the UI still must not compute its OWN second opinion of what these
// findings mean, and now doesn't have to: it can render the one server-computed value instead.
export interface VerificationReport {
  findings: VerificationFinding[];
  headerReconstructionUncertain: boolean;
  textSource: 'NATIVE_PDF' | 'OCR' | 'NATIVE_PLUS_OCR' | null;
  reliabilityStatus: ImportReliabilityStatus | null;
}

// One check's result. `rule` is a stable machine identifier ("BALANCE_CHAIN"), never a label --
// the UI maps it to a renderer, so a new validator is additive rather than another branch.
// `outcome` is that rule's verdict about its OWN domain, not the document's.
// `details` is a per-rule payload: the balance chain reports per-row discrepancies, while checks
// planned next (statement totals, structural) have no row to point at at all.
export interface VerificationFinding {
  rule: string;
  outcome: 'VERIFIED' | 'WARNING' | 'FAILED' | 'NOT_APPLICABLE';
  details: Record<string, unknown>;
}

// The balance chain's own `details` shape. Named here so its renderer is typed rather than
// reaching into an untyped record -- other rules will declare their own.
export interface BalanceChainDetails {
  rowsChecked: number;
  rowsWithBalance: number;
  anchoredOnOpeningBalance: boolean;
  discrepancies: BalanceRowDiscrepancy[];
}

interface BalanceRowDiscrepancy {
  rowIndex: number;
  expectedBalance: number;
  actualBalance: number;
  difference: number;
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
  unparseableRows: UnparseableRow[];
  // Optional and nullable. Null means verification was not performed at all, which is distinct from a report whose
  // finding says NOT_APPLICABLE ("checked; this statement has no running balance to check with").
  // Per section, never merged: one section of a composite statement can verify while another does not.
  verification?: VerificationReport | null;
}

export interface ImportSummary {
  imported: number;
  skipped: number;
  duplicatesDetected: number;
  transfersIdentified: number;
  newMerchantsLearned: number;
  accountsCreated: string[];
  productsCreated: Record<string, number>;
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
  // Credit-card statement entity, roadmap item 6. Null for a non-credit-card statement.
  totalAmountDue: number | null;
  paymentDueDate: string | null;
  transactionsImported: number;
  transactionsSkipped: number;
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
    verification?: VerificationReport | null;
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


/**
 * An existing transaction that a staged row appears to repeat (WI5).
 *
 * `confidence` has one level, 'EXACT', because the backend matches on date AND amount AND
 * description being identical — there is no weaker tier to report, and inventing a spectrum the
 * detector cannot produce would be worse than saying so.
 *
 * `matchCount` above 1 is a signal, and the opposite of the one a filter would draw: several
 * identical existing transactions usually means the user genuinely transacts this repeatedly (a
 * daily fare, a split bill), which is precisely when skipping the row is wrong.
 */
export interface DuplicateMatch {
  existingTransactionId: string;
  existingAccountId: string | null;
  existingDate: string;
  existingDescription: string;
  existingAmount: number;
  existingType: 'INCOME' | 'EXPENSE' | null;
  existingImportedAt: string;
  matchCount: number;
  confidence: 'EXACT';
  reason: string;
}
