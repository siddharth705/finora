import { api, rawApi, type ApiEnvelope } from './client';
import type {

  Account, AccountStatementGroup, BankInfo, Budget, DashboardSummary, DetectedAccountInfo, Goal,
  ImportSummary, ReimportResult, StagedAccountSection, StagedRow, StatementSummary, Transaction,
  WorkspaceSettings, UnparseableRow,
} from '../types';

// Which portal this account belongs to. The same person may hold a USER account and an ADMIN
// account under one email and one mobile number, so login and password reset have to say which
// one they mean. Not an authorization signal -- what an account may do is decided by its roles.
const PORTAL_SCOPE = 'USER';


// Mirrors the backend's AuthDtos.AuthResponse. maskedPhone (see PhoneMasking on the backend) lets
// VerifyPhone.tsx show which number a code was sent to -- e.g. "+•••••••••705" -- so a wrong or
// missing country code on the account is visible on screen instead of silently failing to
// deliver. The frontend fetches the REAL phone number separately (userApi.get(), once
// authenticated) when it actually needs to hand it to Firebase's signInWithPhoneNumber().
export interface AuthResponseDto {
  token: string;
  refreshToken: string;
  email: string;
  fullName: string;
  phoneVerified: boolean;
  maskedPhone: string | null;
}

export const authApi = {
  register: (email: string, password: string, fullName: string, phoneNumber: string) =>
    api.post<AuthResponseDto>('/auth/register', { email, password, fullName, phoneNumber }),
  // `identifier` accepts either an email address or a registered mobile number -- see
  // AuthService.resolveEmailForLogin on the backend, which resolves either down to the
  // account's real email before authenticating.
  login: (identifier: string, password: string) =>
    api.post<AuthResponseDto>('/auth/login', { identifier, password, scope: PORTAL_SCOPE }),
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email, scope: PORTAL_SCOPE }).then((r) => r.data),
  // Reveals the account's real phone number for a valid, unused reset link -- needed to call
  // Firebase Phone Authentication directly (Firebase's own client SDK sends the OTP; this
  // backend never does). token is the same raw reset-link token from forgotPassword.
  resolveResetPasswordPhone: (token: string) =>
    api.post<{ phoneNumber: string }>('/auth/reset-password/phone', { token }).then((r) => r.data),
  // firebaseIdToken is the second factor -- proof of phone access via Firebase Phone
  // Authentication, verified server-side (see AuthService.resetPassword on the backend).
  resetPassword: (token: string, firebaseIdToken: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, firebaseIdToken, newPassword }).then((r) => r.data),
  // Uses a bare axios call (not the shared `api` instance) so a failing/expiring access token
  // on the shared instance can't interfere with the refresh call itself.
  refresh: (refreshToken: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh', { refreshToken }).then((r) => r.data.data),
  logout: (refreshToken: string) =>
    api.post<{ message: string }>('/auth/logout', { refreshToken }).then((r) => r.data),
};

// Just one endpoint now -- there's no backend-triggered "send" step (Firebase's own client SDK
// sends the OTP directly; the frontend already knows the account's real phone number from
// userApi.get()), only verifying the Firebase ID token that results from it.
export const phoneApi = {
  verify: (firebaseIdToken: string) =>
    api.post<{ message: string }>('/phone/verify', { firebaseIdToken }).then((r) => r.data),
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
  //
  // `password` opens a protected statement (most Indian banks e-mail them that way). It goes in
  // the form body, never the query string, so it cannot end up in a server access log or in
  // browser history. Omitted entirely when blank, and harmless when the file turns out not to
  // need one -- so the caller never has to inspect the file to decide whether to send it.
  stagePdf: (file: File, onProgress?: ProgressCallback, password?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (password) form.append('password', password);
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
  // Read-only — no setter path in userApi.update below. phoneNumber/phoneVerified are the
  // OTP-verified registration number (see PhoneMaskingTest/VerifyPhone.tsx); createdAt is a fact
  // about the account, not a preference.
  phoneNumber: string;
  phoneVerified: boolean;
  createdAt: string;
  // Null until the account's password has been changed at least once -- never a guessed
  // fallback date (see the backend User.passwordChangedAt's own doc comment).
  passwordChangedAt: string | null;
}
export const userApi = {
  get: () => api.get<UserSettings>('/users/me').then((r) => r.data),
  update: (body: { lowBalanceThreshold?: number; theme?: string; timezone?: string; fullName?: string }) =>
    api.put<UserSettings>('/users/me', body).then((r) => r.data),
};

// The authenticated, OTP-gated Change Password flow -- see ChangePasswordModal's own doc comment
// for why this is a genuinely separate journey from authApi's forgot-password/reset-password, and
// PasswordChangeService on the backend for the full start -> verify-otp -> complete state machine
// these three calls back. Three separate round trips (not one combined call) so the UI can show
// real progress and the backend can enforce that step N+1 never runs before step N actually
// succeeded server-side. OTP verification itself is Firebase Phone Authentication -- start()
// returns the real phone number to hand to Firebase directly; verifyOtp() sends the resulting ID
// token, never a code.
export const passwordChangeApi = {
  start: (currentPassword: string) =>
    api.post<{ sessionId: string; phoneNumber: string; maskedPhone: string }>(
      '/users/me/password-change/start', { currentPassword }
    ).then((r) => r.data),
  verifyOtp: (sessionId: string, firebaseIdToken: string) =>
    api.post<{ message: string }>(
      '/users/me/password-change/verify-otp', { sessionId, firebaseIdToken }
    ).then((r) => r.data),
  // currentRefreshToken lets the backend positively identify (and exclude) this device from
  // revocation when signOutOtherDevices is true -- an access token alone doesn't carry enough
  // information to know which refresh token belongs to this browser tab.
  complete: (sessionId: string, newPassword: string, signOutOtherDevices: boolean, currentRefreshToken: string) =>
    api.post<{ message: string; otherDevicesSignedOut: boolean }>(
      '/users/me/password-change/complete', { sessionId, newPassword, signOutOtherDevices, currentRefreshToken }
    ).then((r) => r.data),
};

// Self-service view of the caller's own active refresh-token sessions -- backs Settings.tsx's
// Active Sessions list under Security. Mirrors the backend's DeviceSessionDto exactly; browser/
// device/lastSeenIp are best-effort labels captured from whichever request last issued/rotated
// that token, not a durable per-device fingerprint (see RefreshToken's own doc comment on the
// backend), so any of them can legitimately be null.
export interface DeviceSession {
  id: string;
  browser: string | null;
  device: string | null;
  lastSeenIp: string | null;
  lastSeenAt: string | null;
  createdAt: string;
  expiresAt: string;
}
export const deviceApi = {
  list: () => api.get<DeviceSession[]>('/users/me/devices').then((r) => r.data),
  revoke: (id: string) => api.delete(`/users/me/devices/${id}`),
};

// --- Import statistics ---
//
// The only analytics view still exposed to end users -- merchant/rule/relationship/learning
// management and the rest of the analytics views are admin-only now (see the admin portal's
// UserDetail page and the backend's AdminUser*Controller family). This one stays because
// Settings.tsx's Account section shows the signed-in user their own import totals.

export interface ImportStatistics {
  totalStatements: number;
  totalTransactionsImported: number;
  totalTransactionsSkipped: number;
  lastImportedAt: string | null;
}
export const analyticsApi = {
  importStatistics: () =>
    api.get<ImportStatistics>('/analytics/merchants', { params: { view: 'importStatistics' } }).then((r) => r.data),
};

// --- Financial Intelligence Workspace: Dashboard ---

export const workspaceApi = {
  getSettings: () => api.get<WorkspaceSettings>('/workspace/settings').then((r) => r.data),
  updateSettings: (body: { autoApplyConfidenceThreshold: number }) =>
    api.put<WorkspaceSettings>('/workspace/settings', body).then((r) => r.data),
};
