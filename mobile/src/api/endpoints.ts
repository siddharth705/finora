import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { api, rawApi, type ApiEnvelope } from './client';
import { encodeBase64 } from '../lib/base64';
import type {
  Account, AccountStatementGroup, BankInfo, Budget, DashboardSummary, DetectedAccountInfo, Goal,
  ImportSummary, ReimportResult, StagedAccountSection, StagedRow, StatementSummary, Transaction,
  WorkspaceSettings, UnparseableRow,
} from '../types';

// Ported from frontend/src/api/endpoints.ts -- these are plain axios calls with TS types, no DOM
// dependency, so almost everything here is unchanged from the web app. The two exceptions are
// import file upload (web's `File`/FormData vs. RN's `{uri,name,type}` FormData shape) and
// statement file download (web's Blob+<a> click has no native equivalent) -- both marked below
// and left for Phase 3 (Import Flow), which is where the real file-picker/upload/download work
// happens. Keep this file in sync with the web version by hand for every other endpoint.

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
  login: (identifier: string, password: string) =>
    api.post<AuthResponseDto>('/auth/login', { identifier, password }),
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email }).then((r) => r.data),
  resolveResetPasswordPhone: (token: string) =>
    api.post<{ phoneNumber: string }>('/auth/reset-password/phone', { token }).then((r) => r.data),
  resetPassword: (token: string, firebaseIdToken: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, firebaseIdToken, newPassword }).then((r) => r.data),
  // Uses the bare rawApi instance (not `api`) so a failing/expiring access token can't interfere
  // with the refresh call itself.
  refresh: (refreshToken: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh', { refreshToken }).then((r) => r.data.data),
  logout: (refreshToken: string) =>
    api.post<{ message: string }>('/auth/logout', { refreshToken }).then((r) => r.data),
};

export const phoneApi = {
  verify: (firebaseIdToken: string) =>
    api.post<{ message: string }>('/phone/verify', { firebaseIdToken }).then((r) => r.data),
};

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

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

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
  // { ids } rather than a bare array: the endpoint now takes a validated DTO that bounds the
  // list (MAX_BULK_IDS). It previously accepted an unbounded List<UUID> straight off the body.
  bulkDelete: (ids: string[]) => api.post('/transactions/bulk-delete', { ids }),
  bulkRecategorize: (ids: string[], category: string) =>
    api.post('/transactions/bulk-category', { ids, category }),
};

/**
 * Mirrors the backend's ImportDto.ConfirmedRow exactly.
 *
 * Declared against the backend record rather than copied from the web app's own interface, which
 * omits the last two: the web code passes them anyway (excess-property checking doesn't apply once
 * an object literal has been through a variable), so its type understates what it sends. Both are
 * carried straight through from staging and must survive review unchanged, or the ledger loses the
 * statement's reference numbers and running balances.
 */
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
  referenceNumber: string | null;
  balanceAfter: number | null;
}

/**
 * Mirrors the backend's ImportDto.NewAccountRequest.
 *
 * Everything from `detectedProduct` down is echoed back unchanged from what staging detected --
 * the review screen displays these read-only, so there is nothing here for a client to have gotten
 * wrong. Dropping them is silent and expensive: a fixed deposit becomes an empty savings account,
 * and without `productIdentityHash` a re-import cannot tell "the deposit I already hold" from a new
 * one, so it double-counts in net worth.
 *
 * `productIdentityHash` is already a hash by the time the client sees it -- no unmasked account
 * number ever leaves the server -- so echoing it back discloses nothing.
 */
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
  detectedProduct?: string | null;
  productIdentityHash?: string | null;
  principalAmount?: number | null;
  interestRate?: number | null;
  maturityDate?: string | null;
  maturityAmount?: number | null;
  installmentAmount?: number | null;
  installmentsPaid?: number | null;
  installmentsTotal?: number | null;
}

export interface ConfirmPayload {
  sessionId: string;
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
}

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

interface PdfStagingSessionResult {
  sessionId: string;
  multiAccount: boolean;
  staging: StagingResult | null;
  sections: StagedAccountSection[] | null;
}

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

// React Native's FormData has no web `File` type to append -- it accepts a plain
// {uri, name, type} descriptor instead, which is exactly the shape `expo-document-picker`'s
// result already gives you (Phase 3 wires the real picker up to this).
export interface RNFile {
  uri: string;
  name: string;
  type: string;
}

export const importApi = {
  stageCsv: (file: RNFile, onProgress?: ProgressCallback) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    return api
      .post<{ sessionId: string; staging: StagingResult }>('/import/csv/stage', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        ...toUploadProgressConfig(onProgress),
      })
      .then((r) => r.data);
  },
  // `password` opens a protected statement (most Indian banks e-mail them that way). It rides in
  // the form body, never the query string, so it can't reach a server access log. Omitted when
  // blank, and harmless when the file turns out not to need one -- so the caller never has to
  // inspect the file to decide whether to send it.
  stagePdf: (file: RNFile, onProgress?: ProgressCallback, password?: string) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    if (password) form.append('password', password);
    return api
      .post<PdfStagingSessionResult>('/import/pdf/stage', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        ...toUploadProgressConfig(onProgress),
      })
      .then((r) => r.data);
  },
  confirm: (payload: ConfirmPayload) =>
    api.post<ImportSummary>('/import/csv/confirm', payload).then((r) => r.data),
  confirmMulti: (payload: MultiAccountConfirmPayload) =>
    api.post<{ perAccount: ImportSummary[] }>('/import/pdf/confirm-multi', payload).then((r) => r.data),
  listSessions: () => api.get<ImportSessionSummary[]>('/import/sessions').then((r) => r.data),
  getSession: (id: string) =>
    api.get<{ sessionId: string; staging: StagingResult }>(`/import/sessions/${id}`).then((r) => r.data),
  discardSession: (id: string) => api.delete(`/import/sessions/${id}`),
};

export const statementImportsApi = {
  listGroupedByAccount: () => api.get<AccountStatementGroup[]>('/statement-imports').then((r) => r.data),
  detail: (id: string) => api.get<StatementSummary>(`/statement-imports/${id}`).then((r) => r.data),
  transactions: (id: string) => api.get<Transaction[]>(`/statement-imports/${id}/transactions`).then((r) => r.data),
  /**
   * "Download" means something different here than on web. The web app streams the file into a
   * Blob and clicks a synthetic <a download>, neither of which exists on native -- and a file
   * dropped into an app's sandbox is invisible to the user anyway. So this writes the bytes into
   * the app's cache directory and hands the URI to the native share sheet, which is where "save to
   * Files", "mail it to myself" and every other real destination live.
   *
   * Cache rather than documents: the OS may reclaim it, which is correct for a scratch copy the
   * user has already been given a chance to put somewhere permanent. Nothing here re-downloads on
   * its own, so a reclaimed file costs one more tap, not data loss.
   *
   * arraybuffer -> base64 because expo-file-system's write() takes a string; axios on React Native
   * has no Blob to hand over.
   */
  downloadFile: async (id: string, fileName: string) => {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    const res = await api.get<ArrayBuffer>(`/statement-imports/${id}/file`, { responseType: 'arraybuffer' });
    const file = new File(Paths.cache, fileName);
    // A previous share of the same statement leaves the file behind; write() will not overwrite.
    if (file.exists) file.delete();
    file.create();
    file.write(encodeBase64(res.data), { encoding: 'base64' });
    await Sharing.shareAsync(file.uri, {
      mimeType: fileName.toLowerCase().endsWith('.pdf') ? 'application/pdf' : 'text/csv',
      UTI: fileName.toLowerCase().endsWith('.pdf') ? 'com.adobe.pdf' : 'public.comma-separated-values-text',
      dialogTitle: fileName,
    });
  },
  // `password` is only ever needed for a statement originally uploaded as a protected PDF: the
  // stored bytes are still encrypted, and the password used at upload is deliberately never
  // persisted, so it has to be supplied again here. In the body, never the URL -- a document
  // password in a query string is captured by access logs and proxy logs.
  reimport: (id: string, password?: string) =>
    api.post<ReimportResult>(`/statement-imports/${id}/reimport`, password ? { password } : {}).then((r) => r.data),
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
  phoneNumber: string;
  phoneVerified: boolean;
  createdAt: string;
  passwordChangedAt: string | null;
}
export const userApi = {
  get: () => api.get<UserSettings>('/users/me').then((r) => r.data),
  update: (body: { lowBalanceThreshold?: number; theme?: string; timezone?: string; fullName?: string }) =>
    api.put<UserSettings>('/users/me', body).then((r) => r.data),
};

export const passwordChangeApi = {
  start: (currentPassword: string) =>
    api.post<{ sessionId: string; phoneNumber: string; maskedPhone: string }>(
      '/users/me/password-change/start', { currentPassword }
    ).then((r) => r.data),
  verifyOtp: (sessionId: string, firebaseIdToken: string) =>
    api.post<{ message: string }>(
      '/users/me/password-change/verify-otp', { sessionId, firebaseIdToken }
    ).then((r) => r.data),
  complete: (sessionId: string, newPassword: string, signOutOtherDevices: boolean, currentRefreshToken: string) =>
    api.post<{ message: string; otherDevicesSignedOut: boolean }>(
      '/users/me/password-change/complete', { sessionId, newPassword, signOutOtherDevices, currentRefreshToken }
    ).then((r) => r.data),
};

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

export const workspaceApi = {
  getSettings: () => api.get<WorkspaceSettings>('/workspace/settings').then((r) => r.data),
  updateSettings: (body: { autoApplyConfidenceThreshold: number }) =>
    api.put<WorkspaceSettings>('/workspace/settings', body).then((r) => r.data),
};

// --- Device management (Active Sessions) ---
// GET/DELETE /api/v1/users/me/devices -- backend-complete (DeviceController), no web UI yet
// either. See mobile roadmap Phase 5: recommended as a mobile-first screen.
// Mirrors the backend's DeviceSessionDto exactly. Note there's no "is this the current device"
// flag -- the backend doesn't send one, so the UI can't highlight the current session without
// correlating against the stored refresh token itself.
export interface DeviceSession {
  id: string;
  browser: string | null;
  device: string | null;
  lastSeenIp: string | null;
  lastSeenAt: string;
  createdAt: string;
  expiresAt: string;
}
export const devicesApi = {
  list: () => api.get<DeviceSession[]>('/users/me/devices').then((r) => r.data),
  revoke: (id: string) => api.delete<{ message: string }>(`/users/me/devices/${id}`).then((r) => r.data),
};
