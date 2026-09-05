import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { api, rawApi, type ApiEnvelope } from './client';
import { encodeBase64 } from '../lib/base64';
import { decodeUtf8 } from '../lib/utf8';
import { isCanceled, isOffline } from '../lib/apiError';
import type {
  Account, AccountStatementGroup, Budget, DashboardSummary, DetectedAccountInfo, Goal,
  ImportSummary, MerchantGroup, ReimportResult, StagedAccountSection, StagedRow, StatementSummary,
  Transaction, WorkspaceSettings, UnparseableRow,
} from '../types';

// Ported from frontend/src/api/endpoints.ts -- these are plain axios calls with TS types, no DOM
// dependency, so almost everything here is unchanged from the web app. The two exceptions are
// import file upload (web's `File`/FormData vs. RN's `{uri,name,type}` FormData shape) and
// statement file download (web's Blob+<a> click has no native equivalent) -- both marked below
// and left for Phase 3 (Import Flow), which is where the real file-picker/upload/download work
// happens. Keep this file in sync with the web version by hand for every other endpoint.

/**
 * Re-reads an ArrayBuffer-typed error response as the JSON envelope it actually is, so the
 * message survives.
 *
 * Mobile's counterpart to the web app's `withBlobErrorMessage`: any request sent with
 * responseType: 'arraybuffer' gets that response type applied to error responses too. The
 * backend's error body is a normal ApiResponse envelope, but axios hands it over as a raw
 * ArrayBuffer, so every consumer that looks for `.data.message` -- including client.ts's own
 * interceptor -- finds nothing and the actionable text is discarded. Mutates the error in place
 * so the shape callers already expect (`err.response.data.message`) is what they get.
 */
async function withArrayBufferErrorMessage(err: unknown): Promise<unknown> {
  const response = (err as { response?: { data?: unknown } })?.response;
  if (!(response?.data instanceof ArrayBuffer)) return err;
  try {
    const parsed = JSON.parse(decodeUtf8(response.data));
    response.data = { message: parsed?.message, errorCode: parsed?.errorCode };
  } catch {
    // Not JSON (a proxy's HTML error page, a truncated body). Leave a usable message rather than
    // an unreadable ArrayBuffer, which is what the caller had before this existed.
    response.data = { message: 'The download failed and the server did not explain why.' };
  }
  return err;
}

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
  // Identifier-first entry step (Phase 3B) -- resolves an email or mobile number to what the
  // client should show next, without a raw exists boolean. See frontend/src/api/endpoints.ts's
  // own copy: nextAction is 'EXISTS' for an existing account (Phase 7, resolved 2026-08-23: no
  // longer distinguishes which sign-in method it uses), or 'CONTINUE' when there isn't one yet.
  identify: (identifier: string) =>
    api.post<{ nextAction: string }>('/auth/identify', { identifier }).then((r) => r.data),
  // D-23 Phase 2. idToken is the raw credential from @react-native-google-signin/google-signin --
  // verified server-side (GoogleIdTokenVerifierService), never trusted client-side. Same endpoint
  // web's GoogleSignInButton already calls; see frontend/src/api/endpoints.ts's own copy.
  google: (idToken: string) => api.post<AuthResponseDto>('/auth/google', { idToken }),
  // D-23 Phase 2 / D-26 (iOS only). idToken is the raw credential from
  // expo-apple-authentication's signInAsync(). fullName is optional and NOT part of the token --
  // Apple hands it to the CLIENT, not the backend, and only on the user's very first
  // authorization for this app -- see AppleAuthRequest's own doc comment on the backend.
  apple: (idToken: string, fullName?: string) =>
    api.post<AuthResponseDto>('/auth/apple', { idToken, fullName }),
  // Completes the "Welcome back — reactivate your account?" prompt LoginScreen shows after a
  // deactivated account's password checks out -- see AuthContext.reactivate. Returns the same
  // shape as login.
  reactivate: (token: string) =>
    api.post<AuthResponseDto>('/auth/reactivate', { token }),
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email }).then((r) => r.data),
  // BH-015 fix. Not called from any screen yet -- password-reset completion is web-only on
  // mobile today (see ForgotPasswordScreen's own doc comment) -- kept here, signature-matched to
  // the backend contract, for whenever an in-app completion screen is built.
  verifyResetPasswordPhone: (token: string, phoneNumber: string) =>
    api.post<{ message: string }>('/auth/reset-password/phone', { token, phoneNumber }).then((r) => r.data),
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
  // The bulk half of the review backlog. Disjoint from needsReview() above -- the server removes
  // anything it returns here from that list, so the two are rendered together, not as alternatives.
  needsReviewGroups: () =>
    api.get<MerchantGroup[]>('/transactions/groups/needs-review').then((r) => r.data),
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
  // BH-027: "no, these really are two separate transactions." Records a human ruling that
  // outranks the reconciliation engine's own guess -- see TransactionService.confirmNotDuplicate.
  // Mirrors frontend/src/api/endpoints.ts.
  confirmNotDuplicate: (id: string) =>
    api.post<Transaction>(`/transactions/${id}/not-duplicate`).then((r) => r.data),
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
  /** Echoed from StagedRow.rowPosition unchanged -- see that field's own doc comment. */
  rowPosition: number | null;
  /**
   * The user's ANSWER on the duplicate review screen, as opposed to `likelyDuplicate`, which is the
   * engine's GUESS. True only when the engine flagged the row and the person chose "Import anyway".
   *
   * Optional because the backend defaults it to false, and its doc comment names this app as the
   * client that does not send it. That is no longer true, and the field matters more here than the
   * default suggests: without it, reconciliation re-flags the row the moment it lands and strips it
   * from every spend total, so the user's decision shows in the ledger and vanishes from the
   * numbers. See V65 for the measured damage on the web path.
   */
  confirmedNotDuplicate?: boolean;
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
  // Only meaningful to confirmReimport, for a statement whose stored bytes are a password-protected
  // PDF -- see ConfirmRequest's own doc comment on the backend. Every other confirm path ignores it.
  password?: string;
  // Also reimport-only (Track B/B1). Identifies one logical confirm ATTEMPT so the server can
  // refuse a replay of it -- a first-time import needs no key, since its ImportSession is claimed
  // atomically server-side and cannot be confirmed twice. See lib/idempotencyKey.ts.
  idempotencyKey?: string;
}

interface SectionConfirmPayload {
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

export interface StagingResult {
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

// timeout: 0 overrides client.ts's default 30s timeout -- a statement upload over a slow mobile
// connection can legitimately take longer than that, and unlike an ordinary JSON call, this one
// already gives the user live proof it's still working via onUploadProgress. Applies whether or
// not a progress callback was actually passed, since the upload itself is what can be slow.
function toUploadProgressConfig(onProgress?: ProgressCallback, signal?: AbortSignal) {
  return {
    timeout: 0,
    // Why a signal matters MORE here than on an ordinary call: this is the one request with
    // `timeout: 0`, so nothing else will ever end it. Without a way to abort, a stalled upload on a
    // dead connection hangs until the OS tears the socket down, with the progress bar frozen and no
    // way out of the screen.
    ...(signal ? { signal } : {}),
    ...(onProgress
      ? {
          onUploadProgress: (e: { loaded: number; total?: number }) => {
            if (e.total) onProgress(Math.round((e.loaded / e.total) * 100));
          },
        }
      : {}),
  };
}

// React Native's FormData has no web `File` type to append -- it accepts a plain
// {uri, name, type} descriptor instead, which is exactly the shape `expo-document-picker`'s
// result already gives you (Phase 3 wires the real picker up to this).
export interface RNFile {
  uri: string;
  name: string;
  type: string;
}

/**
 * One retry, only for a genuine transport-layer failure (no response reached the client at all --
 * see isOffline()'s own doc comment).
 *
 * The document picker (`pickStatement()` in lib/statementFile.ts) hands control to a separate OS
 * activity and back. Verified against a real device, not assumed: the moment that activity
 * returns, an upload started immediately can fail with axios's ERR_NETWORK even though the file is
 * confirmed present on disk and every other endpoint reached from the same screen moments earlier
 * or later succeeds -- the app's process is briefly resumed before the OS has finished restoring
 * its network callback registration (visible in logcat as a ConnectivityService RemoteException
 * for this app's own request package right after the picker activity exits). A fixed delay before
 * every upload would tax the common case to paper over a one-off timing gap; retrying once, only
 * on the specific error shape this gap produces, costs nothing when the gap isn't there and
 * recovers when it is.
 */
async function stageWithRetry<T>(attempt: () => Promise<T>): Promise<T> {
  try {
    return await attempt();
  } catch (e) {
    // Cancel is checked first and deliberately: a cancelled request has no response and so passes
    // isOffline's test, which meant this retried the very upload the user just cancelled -- the
    // file went up a second time and the cancel appeared to do nothing.
    if (isCanceled(e)) throw e;
    if (!isOffline(e)) throw e;
    return attempt();
  }
}

export const importApi = {
  // No explicit Content-Type header on either upload below: 'multipart/form-data' with no boundary
  // is invalid HTTP (the multipart parser needs one), and axios only computes the correct
  // boundary-included header when nothing has already set Content-Type. This was previously set by
  // hand -- turned out to be a red herring for the real bug below (see stageWithRetry's own doc
  // comment), but wrong regardless of that, since a manual header without a boundary can never be
  // valid multipart.
  stageCsv: (file: RNFile, onProgress?: ProgressCallback, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    return stageWithRetry(() =>
      api
        .post<{ sessionId: string; staging: StagingResult }>('/import/csv/stage', form, toUploadProgressConfig(onProgress, signal))
        .then((r) => r.data)
    );
  },
  // `password` opens a protected statement (most Indian banks e-mail them that way). It rides in
  // the form body, never the query string, so it can't reach a server access log. Omitted when
  // blank, and harmless when the file turns out not to need one -- so the caller never has to
  // inspect the file to decide whether to send it.
  stagePdf: (file: RNFile, onProgress?: ProgressCallback, password?: string, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    if (password) form.append('password', password);
    return stageWithRetry(() =>
      api
        .post<PdfStagingSessionResult>('/import/pdf/stage', form, toUploadProgressConfig(onProgress, signal))
        .then((r) => r.data)
    );
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
    let res;
    try {
      res = await api.get<ArrayBuffer>(`/statement-imports/${id}/file`, { responseType: 'arraybuffer' });
    } catch (err) {
      // responseType: 'arraybuffer' applies to ERROR responses too, so on a 4xx/5xx error.response.data
      // is an ArrayBuffer rather than the parsed {message, errorCode} envelope. client.ts's interceptor
      // tests error.response?.data?.message, an ArrayBuffer has no such property, the normalising
      // branch is skipped, and the caller gets an error with no readable detail -- for a path whose
      // backend failures are specific and actionable ("Statement ... is in object storage, but no
      // storage provider is configured"). Decoding the buffer back to text restores the envelope the
      // rest of the app expects.
      throw await withArrayBufferErrorMessage(err);
    }
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

interface NetWorthSnapshotPoint {
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

interface CategoryMover {
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

// Phase 4 (docs/proposals/authentication-account-security-review.md). Ported from
// frontend/src/api/endpoints.ts's identical emailChangeApi -- see ChangeEmailModal.tsx's own doc
// comment for why start() is the only call this app's "form" step needs: verify()/complete() run
// from the emailed link (VerifyEmailChangeScreen), not from anything typed in-app.
export const emailChangeApi = {
  start: (currentPassword: string | null, googleIdToken: string | null, appleIdToken: string | null, newEmail: string) =>
    api.post<{ sessionId: string; devVerifyLink: string | null }>(
      '/users/me/email-change/start', { currentPassword, googleIdToken, appleIdToken, newEmail }
    ).then((r) => r.data),
  verify: (sessionId: string, token: string) =>
    api.post<{ message: string }>('/users/me/email-change/verify', { sessionId, token }).then((r) => r.data),
  complete: (sessionId: string) =>
    api.post<{ message: string; email: string }>('/users/me/email-change/complete', { sessionId }).then((r) => r.data),
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

// --- Push notification device tokens (Task 14) ---
// Mirrors backend DeviceTokenController exactly: POST /device-tokens registers this device's FCM
// token, POST /device-tokens/revoke removes it. Revoke is a POST, not a DELETE-with-body -- the
// backend has no precedent for a DELETE carrying a body (some proxies strip it), and the client
// identifies the token to revoke by its own raw string, never a server-side row id it was never
// given. `platform` must be exactly 'ANDROID' or 'IOS' (backend validates
// @Pattern(regexp = "ANDROID|IOS")) -- but it is NOT what routes delivery: iOS devices register an
// FCM token too (via @react-native-firebase/messaging), and FCM relays every send to Apple's APNs
// on this project's behalf (see backend FcmPushProvider's class doc, Ruling O / Task 11). This
// field is retained for diagnostics and per-platform delivery metrics only.
export type DevicePlatform = 'ANDROID' | 'IOS';
export interface RegisteredDeviceToken {
  id: string;
  platform: DevicePlatform;
  registeredAt: string;
}
export const deviceTokensApi = {
  register: (body: { token: string; platform: DevicePlatform }) =>
    api.post<RegisteredDeviceToken>('/device-tokens', body).then((r) => r.data),
  // Backend returns ApiResponse.ok(null, "Device token revoked") -- the response-envelope unwrap
  // (see client.ts) yields the inner `data`, which is null, not a { message } object.
  revoke: (body: { token: string }) =>
    api.post<null>('/device-tokens/revoke', body).then((r) => r.data),
};

// Support, Help & Feedback v1 (Phase 8, mobile). Mirrors frontend/src/api/endpoints.ts's own
// SupportTicketCategory/Status and FeedbackType/Context unions exactly -- see that file's comment
// for why a value added on one side with nothing here to render it is worth guarding against at
// compile time.
export type SupportTicketCategory =
  | 'STATEMENT_IMPORT' | 'CATEGORIZATION' | 'ACCOUNT_LINKING' | 'DATA_ACCURACY' | 'TECHNICAL_ISSUE' | 'OTHER';
export type SupportTicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
export type ClientPlatform = 'WEB' | 'MOBILE_ANDROID' | 'MOBILE_IOS';

export interface SupportTicketAttachmentSummary {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

export interface SupportTicketSummary {
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

export interface SupportTicketDetail extends SupportTicketSummary {
  description: string;
  source: ClientPlatform;
  appVersion: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  attachments: SupportTicketAttachmentSummary[];
}

export const supportApi = {
  // No explicit Content-Type header, same reasoning as importApi.stageCsv/stagePdf above: a
  // hand-set 'multipart/form-data' with no boundary is invalid HTTP, and axios only computes the
  // correct boundary when nothing has already set Content-Type. Wrapped in stageWithRetry for the
  // same reason those two are: this follows immediately after a DocumentPicker handoff when an
  // attachment is attached, and is the exact timing gap that helper exists for.
  create: (payload: { category: SupportTicketCategory; subject: string; description: string; file?: RNFile | null }) => {
    const form = new FormData();
    form.append('category', payload.category);
    form.append('subject', payload.subject);
    form.append('description', payload.description);
    if (payload.file) form.append('file', payload.file as unknown as Blob);
    return stageWithRetry(() =>
      api.post<SupportTicketDetail>('/support/tickets', form).then((r) => r.data)
    );
  },
  list: (page = 0, size = 25) =>
    api.get<PagedResponse<SupportTicketSummary>>('/support/tickets', { params: { page, size } }).then((r) => r.data),
  detail: (id: string) => api.get<SupportTicketDetail>(`/support/tickets/${id}`).then((r) => r.data),
  /** Same pattern as statementImportsApi.downloadFile: write the bytes into the cache directory
   *  and hand the URI to the native share sheet -- there is no in-sandbox "download" a user could
   *  otherwise find. */
  downloadAttachment: async (ticketId: string, attachmentId: string, filename: string, contentType: string) => {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    let res;
    try {
      res = await api.get<ArrayBuffer>(`/support/tickets/${ticketId}/attachments/${attachmentId}`, { responseType: 'arraybuffer' });
    } catch (err) {
      // Same fix as statementImportsApi.downloadFile -- see withArrayBufferErrorMessage's own doc
      // comment above for why responseType: 'arraybuffer' loses the server's real error message.
      throw await withArrayBufferErrorMessage(err);
    }
    const file = new File(Paths.cache, filename);
    if (file.exists) file.delete();
    file.create();
    file.write(encodeBase64(res.data), { encoding: 'base64' });
    await Sharing.shareAsync(file.uri, { mimeType: contentType, dialogTitle: filename });
  },
};

export type FeedbackType = 'BUG' | 'FEATURE_REQUEST' | 'IMPROVEMENT' | 'GENERAL';
export type FeedbackContext =
  | 'DASHBOARD' | 'TRANSACTIONS' | 'REPORTS' | 'BUDGETS' | 'GOALS' | 'IMPORT_FLOW' | 'ACCOUNTS' | 'SETTINGS' | 'HELP' | 'OTHER';

export interface FeedbackSummary {
  id: string;
  userId: string;
  type: FeedbackType;
  context: FeedbackContext;
  source: ClientPlatform;
  message: string;
  createdAt: string;
}

export const feedbackApi = {
  submit: (payload: { type: FeedbackType; context: FeedbackContext; message: string }) =>
    api.post<FeedbackSummary>('/feedback', payload).then((r) => r.data),
};
