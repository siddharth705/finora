// Ported verbatim from frontend/src/types/index.ts -- these are plain data interfaces mirroring
// backend DTOs, no DOM/web dependency, so they port to React Native unchanged. Keep this file in
// sync with the web version by hand until/unless the two frontends share a types package.

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
  accountHolderName?: string | null;
  accountNumberMasked?: string | null;
  branchName?: string | null;
  ifscCode?: string | null;
  bank: BankInfo;
  lastImportedAt: string | null;
  lastStatementPeriodStart: string | null;
  lastStatementPeriodEnd: string | null;
  statementsCount: number;
  transactionsCount: number;
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
  categorySource: 'learned' | 'rule' | 'user_rule' | 'global_rule' | 'default' | 'file';
  ruleId: string | null;
  likelyDuplicate: boolean;
  referenceNumber: string | null;
  balanceAfter: number | null;
}

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
  duplicateCount: number;
}

export interface AccountStatementGroup {
  accountId: string;
  accountName: string;
  accountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT' | 'UNKNOWN';
  bank: BankInfo;
  statements: StatementSummary[];
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

export interface WorkspaceSettings {
  autoApplyConfidenceThreshold: number;
  updatedAt: string | null;
}
