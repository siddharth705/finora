import { api, rawApi, type ApiEnvelope } from './client';
import type {
  Account, AccountStatementGroup, BankInfo, Budget, DashboardSummary, DetectedAccountInfo, Goal,
  ImportSummary, ReimportResult, StagedAccountSection, StagedRow, StatementSummary, Transaction,
  Merchant, MerchantAuditEntry, Rule, Relationship, WorkspaceSettings, AuditLogEntry, UnparseableRow,
} from '../types';

export const authApi = {
  register: (email: string, password: string, fullName: string, phoneNumber: string) =>
    api.post('/auth/register', { email, password, fullName, phoneNumber }),
  // `identifier` accepts either an email address or a registered mobile number -- see
  // AuthService.resolveEmailForLogin on the backend, which resolves either down to the
  // account's real email before authenticating.
  login: (identifier: string, password: string) =>
    api.post('/auth/login', { identifier, password }),
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email }).then((r) => r.data),
  // Second factor for password reset -- the reset token alone (proof of email access) is no
  // longer sufficient; a phone OTP (proof of phone access) is required too, same principle as
  // phone verification elsewhere. token is the same raw reset-link token from forgotPassword.
  requestPasswordResetOtp: (token: string) =>
    api.post<{ message: string; devOtp: string | null }>('/auth/reset-password/request-otp', { token }).then((r) => r.data),
  resetPassword: (token: string, otp: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, otp, newPassword }).then((r) => r.data),
  // Uses a bare axios call (not the shared `api` instance) so a failing/expiring access token
  // on the shared instance can't interfere with the refresh call itself.
  refresh: (refreshToken: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh', { refreshToken }).then((r) => r.data.data),
  logout: (refreshToken: string) =>
    api.post<{ message: string }>('/auth/logout', { refreshToken }).then((r) => r.data),
};

export const phoneApi = {
  sendOtp: () =>
    api.post<{ message: string; devOtp: string | null }>('/phone/send-otp').then((r) => r.data),
  verifyOtp: (otp: string) =>
    api.post<{ verified: boolean; message: string }>('/phone/verify-otp', { otp }).then((r) => r.data),
};

// Mirrors the backend's AccountDto.CreateRequest -- a distinct shape from Account itself
// (which carries server-computed fields like `bank`/`status`/`lastImportedAt` that a create/
// update request never sends), so bankId can be included without fighting Partial<Account>'s
// excess-property checks against a response-shaped type.
export interface AccountRequest {
  name: string;
  accountType: string;
  balance?: number;
  creditLimit?: number;
  dueDate?: string;
  investmentKind?: string;
  accountHolderName?: string;
  accountNumberMasked?: string;
  bankId?: string;
  branchName?: string;
  ifscCode?: string;
}

export const accountsApi = {
  list: () => api.get<Account[]>('/accounts').then((r) => r.data),
  create: (body: AccountRequest) => api.post<Account>('/accounts', body).then((r) => r.data),
  update: (id: string, body: AccountRequest) => api.put<Account>(`/accounts/${id}`, body).then((r) => r.data),
  remove: (id: string) => api.delete(`/accounts/${id}`),
};

export const banksApi = {
  // q is optional -- omitted returns every registered bank. Powers the "Search Bank" step of
  // manual account creation (see Setup.tsx) without hardcoding a bank list client-side.
  list: (q?: string) => api.get<BankInfo[]>('/banks', { params: q ? { q } : undefined }).then((r) => r.data),
};

export interface TransactionFilters {
  accountId?: string;
  categoryId?: string;
  type?: string;
  dateFrom?: string;
  dateTo?: string;
  amountMin?: number;
  amountMax?: number;
  keyword?: string;
  page?: number;
  size?: number;
  sortField?: string;
  sortDir?: string;
}

// Mirrors the backend's com.finora.dto.PagedResponse<T> -- a real total, not just "however many
// rows came back on this page," so the Ledger can show "Showing 1-10 of 4,213" and disable
// Previous/Next correctly at either end instead of guessing from result count vs. page size.
export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// Full-edit payload for the Transactions page's Edit action. All fields optional/nullable —
// only send what actually changed (see TransactionDto.UpdateRequest, which the backend applies
// as "update this field if non-null"). Deliberately no accountId — see that DTO's doc comment.
export interface UpdateTransactionPayload {
  date?: string | null;
  description?: string | null;
  merchant?: string | null;
  amount?: number | null;
  type?: 'INCOME' | 'EXPENSE' | null;
  categoryName?: string | null;
  notes?: string | null;
  tags?: string[] | null;
}

export const transactionsApi = {
  search: (filters: TransactionFilters) =>
    api.get<PagedResponse<Transaction>>('/transactions', { params: filters }).then((r) => r.data),
  needsReview: () => api.get<Transaction[]>('/transactions/needs-review').then((r) => r.data),
  create: (body: unknown) => api.post<Transaction>('/transactions', body).then((r) => r.data),
  update: (id: string, body: UpdateTransactionPayload) =>
    api.put<Transaction>(`/transactions/${id}`, body).then((r) => r.data),
  updateCategory: (id: string, category: string) =>
    api.patch<Transaction>(`/transactions/${id}/category`, { category }).then((r) => r.data),
  remove: (id: string) => api.delete(`/transactions/${id}`),
  bulkDelete: (ids: string[]) => api.post('/transactions/bulk-delete', ids),
  bulkRecategorize: (ids: string[], category: string) =>
    api.post('/transactions/bulk-category', { ids, category }),
};

export interface ConfirmedRowPayload {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  category: string;
  include: boolean;
  categorySource: string;
  ruleId: string | null;
  likelyDuplicate: boolean;
}

export interface NewAccountPayload {
  name: string;
  accountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT';
  openingBalance: number | null;
  creditLimit: number | null;
  dueDate: string | null;
  accountHolderName?: string | null;
  accountNumberMasked?: string | null;
  bankId?: string | null;
  branchName?: string | null;
  ifscCode?: string | null;
}

export interface ConfirmPayload {
  sessionId: string;
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
}

// One account's worth of reviewed rows within a MultiAccountConfirmPayload -- same shape as
// ConfirmPayload minus sessionId (shared once at the top level instead of repeated per section).
export interface SectionConfirmPayload {
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
}

export interface MultiAccountConfirmPayload {
  sessionId: string;
  sections: SectionConfirmPayload[];
}

export interface ImportSessionSummary {
  id: string;
  fileName: string;
  rowCount: number;
  createdAt: string;
  expiresAt: string;
}

interface StagingResult {
  rows: StagedRow[];
  totalParsed: number;
  flaggedDuplicates: number;
  detectedAccount: DetectedAccountInfo;
  unparseableRows: UnparseableRow[];
}

// A PDF upload can now detect more than one account section in a single file (e.g. an
// HSBC-style "Composite Statement" bundling a savings account and a credit-card account) --
// see ImportDto.PdfStagingSessionResponse on the backend. Exactly one of staging/sections is
// populated, selected by multiAccount: the common single-account case (and every CSV upload,
// which can never be multi-account) still gets `staging` exactly as before; a detected
// multi-account PDF gets `sections` instead.
interface PdfStagingSessionResult {
  sessionId: string;
  multiAccount: boolean;
  staging: StagingResult | null;
  sections: StagedAccountSection[] | null;
}

// Reports 0-100 upload progress via axios's onUploadProgress -- purely the network-transfer
// portion (the callback can't see server-side parsing time after the bytes finish sending), so
// callers should treat 100% as "upload done, processing," not "fully complete."
type ProgressCallback = (percent: number) => void;

function toUploadProgressConfig(onProgress?: ProgressCallback) {
  return onProgress
    ? {
        onUploadProgress: (e: { loaded: number; total?: number }) => {
          if (e.total) onProgress(Math.round((e.loaded / e.total) * 100));
        },
      }
    : {};
}

// ADR-0002: the staged review now survives a dropped session -- the backend persists what gets
// staged (file bytes included) rather than that state living only in this response and whatever
// this page holds in memory afterward. sessionId is what ties stage -> confirm together now,
// instead of re-uploading the file a second time at confirm.
export const importApi = {
  stageCsv: (file: File, onProgress?: ProgressCallback) => {
    const form = new FormData();
    form.append('file', file);
    return api
      .post<{ sessionId: string; staging: StagingResult }>('/import/csv/stage', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        ...toUploadProgressConfig(onProgress),
      })
      .then((r) => r.data);
  },
  // PDF Milestone 1 (see backend com.finora.imports.pdf package doc) -- digital/text-based bank
  // statements only, no OCR/scanned PDFs. Response shape changed from the CSV-shared
  // {sessionId, staging} to PdfStagingSessionResult to carry a multi-account PDF's several
  // detected sections -- see that type's own doc comment.
  stagePdf: (file: File, onProgress?: ProgressCallback) => {
    const form = new FormData();
    form.append('file', file);
    return api
      .post<PdfStagingSessionResult>('/import/pdf/stage', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        ...toUploadProgressConfig(onProgress),
      })
      .then((r) => r.data);
  },
  // Plain JSON now, not multipart -- the file no longer needs to reach the backend a second time;
  // it's already persisted server-side from staging, looked up via payload.sessionId. Exactly one
  // of existingAccountId / newAccount should be set — see ImportDto.ConfirmRequest on the
  // backend, which is exactly what makes an import auto-create its account when needed.
  confirm: (payload: ConfirmPayload) =>
    api.post<ImportSummary>('/import/csv/confirm', payload).then((r) => r.data),
  // Confirms every detected account section of a multi-account PDF staging session together --
  // used only when stagePdf() returned multiAccount: true. See ImportService.confirmMultiSection
  // on the backend, which loops the existing single-account confirm() once per section rather
  // than duplicating that logic.
  confirmMulti: (payload: MultiAccountConfirmPayload) =>
    api.post<{ perAccount: ImportSummary[] }>('/import/pdf/confirm-multi', payload).then((r) => r.data),
  // "Your unfinished imports" -- lets the UI offer to resume a staged-but-not-yet-confirmed
  // session (e.g. after a reload) instead of it silently sitting there until it expires.
  listSessions: () => api.get<ImportSessionSummary[]>('/import/sessions').then((r) => r.data),
  getSession: (id: string) =>
    api.get<{ sessionId: string; staging: StagingResult }>(`/import/sessions/${id}`).then((r) => r.data),
  discardSession: (id: string) => api.delete(`/import/sessions/${id}`),
};

export const statementImportsApi = {
  listGroupedByAccount: () => api.get<AccountStatementGroup[]>('/statement-imports').then((r) => r.data),
  detail: (id: string) => api.get<StatementSummary>(`/statement-imports/${id}`).then((r) => r.data),
  transactions: (id: string) => api.get<Transaction[]>(`/statement-imports/${id}/transactions`).then((r) => r.data),
  // A plain <a href> can't carry the Bearer token, so this goes through the same authenticated
  // axios instance as everything else and triggers the browser download client-side instead.
  downloadFile: async (id: string, fileName: string) => {
    const res = await api.get(`/statement-imports/${id}/file`, { responseType: 'blob' });
    const url = window.URL.createObjectURL(res.data as Blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    window.URL.revokeObjectURL(url);
  },
  reimport: (id: string) => api.post<ReimportResult>(`/statement-imports/${id}/reimport`).then((r) => r.data),
  // Plain JSON, unlike the first-time confirm — the file is already stored server-side from the
  // original import, nothing to re-upload.
  confirmReimport: (id: string, payload: Omit<ConfirmPayload, 'newAccount' | 'sessionId'>) =>
    api.post<ImportSummary>(`/statement-imports/${id}/reimport/confirm`, { ...payload, newAccount: null }).then((r) => r.data),
  remove: (id: string) => api.delete(`/statement-imports/${id}`),
};

export const budgetsApi = {
  list: () => api.get<Budget[]>('/budgets').then((r) => r.data),
  upsert: (categoryName: string, monthlyLimit: number) =>
    api.put<Budget>('/budgets', { categoryName, monthlyLimit }).then((r) => r.data),
};

export const goalsApi = {
  list: () => api.get<Goal[]>('/goals').then((r) => r.data),
  create: (body: Partial<Goal>) => api.post<Goal>('/goals', body).then((r) => r.data),
  addContribution: (id: string, amount: number) =>
    api.post<Goal>(`/goals/${id}/contributions`, { amount }).then((r) => r.data),
  remove: (id: string) => api.delete(`/goals/${id}`),
};

export interface CategoryOption {
  id: string;
  name: string;
  isSystem: boolean;
}
export const categoriesApi = {
  list: () => api.get<CategoryOption[]>('/categories').then((r) => r.data),
};

export const dashboardApi = {
  summary: () => api.get<DashboardSummary>('/dashboard/summary').then((r) => r.data),
};

export interface NetWorthSnapshotPoint {
  date: string;
  netWorth: number;
}
export interface NetWorthData {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
  history: NetWorthSnapshotPoint[];
}
export const networthApi = {
  current: () => api.get<NetWorthData>('/networth').then((r) => r.data),
  saveSnapshot: () => api.post<NetWorthData>('/networth/snapshot').then((r) => r.data),
};

export interface CategoryMover {
  category: string;
  current: number;
  priorAverage: number;
  pctChange: number | null;
}
export interface InsightsData {
  sentences: string[];
  movers: CategoryMover[];
}
export interface RecurringItem {
  merchant: string;
  label: string;
  averageAmount: number;
  occurrences: number;
  lastDate: string;
  nextEstimate: string;
}
export const recurringApi = {
  list: () => api.get<RecurringItem[]>('/recurring').then((r) => r.data),
};

export const insightsApi = {
  get: () => api.get<InsightsData>('/insights').then((r) => r.data),
};

export interface ReportData {
  month: string;
  income: number;
  expense: number;
  categories: { category: string; amount: number }[];
}
export const reportsApi = {
  availableMonths: () => api.get<string[]>('/reports/months').then((r) => r.data),
  forMonth: (month: string) => api.get<ReportData>('/reports', { params: { month } }).then((r) => r.data),
};

export interface UserSettings {
  email: string;
  fullName: string;
  lowBalanceThreshold: number;
  theme: string;
  timezone: string;
}
export const userApi = {
  get: () => api.get<UserSettings>('/users/me').then((r) => r.data),
  update: (body: { lowBalanceThreshold?: number; theme?: string; timezone?: string }) =>
    api.put<UserSettings>('/users/me', body).then((r) => r.data),
};

// --- Merchant Management (docs/financial-intelligence-engine-spec.md §5) ---

export const merchantsApi = {
  list: () => api.get<Merchant[]>('/merchants').then((r) => r.data),
  get: (id: string) => api.get<Merchant>(`/merchants/${id}`).then((r) => r.data),
  audit: (id: string) => api.get<MerchantAuditEntry[]>(`/merchants/${id}/audit`).then((r) => r.data),
  update: (id: string, body: { canonicalName?: string; website?: string }) =>
    api.patch<Merchant>(`/merchants/${id}`, body).then((r) => r.data),
  merge: (id: string, mergeFromMerchantId: string) =>
    api.post<Merchant>(`/merchants/${id}/merge`, { mergeFromMerchantId }).then((r) => r.data),
  confirmCategory: (merchantId: string, categoryId: string, applyToTransactionId: string) =>
    api.post<Merchant>(`/merchants/${merchantId}/confirm-category`, { categoryId, applyToTransactionId }).then((r) => r.data),
  undo: (id: string) => api.post<Merchant>(`/merchants/${id}/undo`).then((r) => r.data),
  // Financial Intelligence Workspace, Learning Engine module -- see
  // MerchantLearningService.reset()'s own doc comment for how this differs from undo().
  resetLearning: (id: string) => api.post<Merchant>(`/merchants/${id}/reset-learning`).then((r) => r.data),
};

// --- Learning Engine (Financial Intelligence Workspace) ---

export interface LearningTimelineEntry {
  id: string;
  merchantId: string;
  merchantName: string;
  action: 'LEARNED' | 'CORRECTED' | 'UNDONE' | 'MERGED' | 'RESET';
  previousCategoryName: string | null;
  newCategoryName: string | null;
  createdAt: string;
}
export interface LearningSummary {
  learnedMerchants: number;
  totalConfirmations: number;
  correctedCount: number;
  resetCount: number;
}
export const learningApi = {
  timeline: () => api.get<LearningTimelineEntry[]>('/learning/timeline').then((r) => r.data),
  summary: () => api.get<LearningSummary>('/learning/summary').then((r) => r.data),
};

// --- Rule Engine (docs/rule-engine-relationship-engine-eds.md) ---

export interface RuleCreateRequest {
  field: string;
  operator: string;
  comparisonValue: string;
  actionType: string;
  actionValue?: string;
  priority?: number;
}
export interface RuleUpdateRequest {
  field?: string;
  operator?: string;
  comparisonValue?: string;
  actionType?: string;
  actionValue?: string;
  priority?: number;
  enabled?: boolean;
}
export const rulesApi = {
  list: () => api.get<Rule[]>('/rules').then((r) => r.data),
  create: (body: RuleCreateRequest) => api.post<Rule>('/rules', body).then((r) => r.data),
  update: (id: string, body: RuleUpdateRequest) => api.put<Rule>(`/rules/${id}`, body).then((r) => r.data),
  remove: (id: string) => api.delete(`/rules/${id}`),
};

// --- Relationship Engine (docs/rule-engine-relationship-engine-eds.md §3.3) ---

export interface RelationshipCreateRequest {
  label: string;
  relationshipType: string;
  linkedAccountId?: string;
  identifiers: { identifierType: string; identifierValue: string }[];
}
// Every field optional -- only supplied ones change, same partial-update convention as the
// backend's RelationshipDto.UpdateRequest. identifiers, when supplied, REPLACES the relationship's
// whole identifier list rather than appending -- see that record's own doc comment.
export interface RelationshipUpdateRequest {
  label?: string;
  relationshipType?: string;
  linkedAccountId?: string;
  identifiers?: { identifierType: string; identifierValue: string }[];
}
export const relationshipsApi = {
  list: () => api.get<Relationship[]>('/relationships').then((r) => r.data),
  create: (body: RelationshipCreateRequest) => api.post<Relationship>('/relationships', body).then((r) => r.data),
  update: (id: string, body: RelationshipUpdateRequest) => api.put<Relationship>(`/relationships/${id}`, body).then((r) => r.data),
  merge: (id: string, mergeFromRelationshipId: string) =>
    api.post<Relationship>(`/relationships/${id}/merge`, { mergeFromRelationshipId }).then((r) => r.data),
  transactions: (id: string) => api.get<Transaction[]>(`/relationships/${id}/transactions`).then((r) => r.data),
  remove: (id: string) => api.delete(`/relationships/${id}`),
};

// --- Merchant Analytics (docs/financial-intelligence-engine-spec.md §5.7) ---

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
export interface ImportStatistics {
  totalStatements: number;
  totalTransactionsImported: number;
  totalTransactionsSkipped: number;
  lastImportedAt: string | null;
}
export interface LearningGrowthPoint {
  month: string;
  learnedCount: number;
  correctedCount: number;
}
export const analyticsApi = {
  topMerchants: (month?: string) =>
    api.get<TopMerchantPoint[]>('/analytics/merchants', { params: { view: 'topMerchants', month } }).then((r) => r.data),
  trend: (month?: string) =>
    api.get<TrendPoint[]>('/analytics/merchants', { params: { view: 'trend', month } }).then((r) => r.data),
  categoryConfidence: () =>
    api.get<CategoryConfidencePoint[]>('/analytics/merchants', { params: { view: 'categoryConfidence' } }).then((r) => r.data),
  // Financial Intelligence Workspace, Analytics module -- same one-endpoint-many-views route as
  // the three above, see AnalyticsController's own doc comment.
  topCategories: (month?: string) =>
    api.get<TopCategoryPoint[]>('/analytics/merchants', { params: { view: 'topCategories', month } }).then((r) => r.data),
  importStatistics: () =>
    api.get<ImportStatistics>('/analytics/merchants', { params: { view: 'importStatistics' } }).then((r) => r.data),
  learningGrowth: () =>
    api.get<LearningGrowthPoint[]>('/analytics/merchants', { params: { view: 'learningGrowth' } }).then((r) => r.data),
};

// --- Financial Intelligence Workspace: Dashboard + Activity Timeline ---

export const workspaceApi = {
  getSettings: () => api.get<WorkspaceSettings>('/workspace/settings').then((r) => r.data),
  updateSettings: (body: { autoApplyConfidenceThreshold: number }) =>
    api.put<WorkspaceSettings>('/workspace/settings', body).then((r) => r.data),
};

export const activityApi = {
  list: () => api.get<AuditLogEntry[]>('/activity').then((r) => r.data),
};
