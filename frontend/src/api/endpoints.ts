import { api, rawApi, type ApiEnvelope } from './client';
import { downloadBlob } from '../lib/download';
import type {

  Account, AccountStatementGroup, BankInfo, Budget, DashboardSummary, DetectedAccountInfo, FinancialJourney, Goal,
  ImportSummary, ReimportResult, StagedAccountSection, StagedRow, StatementSummary, Transaction,
  WorkspaceSettings, UnparseableRow, VerificationReport,
} from '../types';

// Which portal this account belongs to. The same person may hold a USER account and an ADMIN
// account under one email and one mobile number, so login and password reset have to say which
// one they mean. Not an authorization signal -- what an account may do is decided by its roles.
const PORTAL_SCOPE = 'USER';

/**
 * Re-reads a blob-typed error response as the JSON envelope it actually is, so the message
 * survives.
 *
 * Any request sent with responseType: 'blob' gets that response type applied to error responses
 * too. The backend's error body is a normal ApiResponse envelope, but axios hands it over as a
 * Blob, so every consumer that looks for `.data.message` — including client.ts's own interceptor —
 * finds nothing and the actionable text is discarded. Mutates the error in place so the shape
 * callers already expect (`err.response.data.message`) is what they get.
 */
async function withBlobErrorMessage(err: unknown): Promise<unknown> {
  const response = (err as { response?: { data?: unknown } })?.response;
  if (!(response?.data instanceof Blob)) return err;
  try {
    const parsed = JSON.parse(await response.data.text());
    response.data = { message: parsed?.message, errorCode: parsed?.errorCode };
  } catch {
    // Not JSON (a proxy's HTML error page, a truncated body). Leave a usable message rather than
    // an unreadable Blob, which is what the caller had before this existed.
    response.data = { message: 'The download failed and the server did not explain why.' };
  }
  return err;
}


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
  // referralCode: D-28 PR4-C. Optional -- Register.tsx passes it only when the page was reached
  // via a referral link's `?ref=` param. Omitted (undefined) for the overwhelming majority of
  // registrations, same as the backend's own RegisterRequest.referralCode.
  register: (email: string, password: string, fullName: string, phoneNumber: string, referralCode?: string) =>
    api.post<AuthResponseDto>('/auth/register', { email, password, fullName, phoneNumber, referralCode }),
  // `identifier` accepts either an email address or a registered mobile number -- see
  // AuthService.resolveEmailForLogin on the backend, which resolves either down to the
  // account's real email before authenticating.
  login: (identifier: string, password: string) =>
    api.post<AuthResponseDto>('/auth/login', { identifier, password, scope: PORTAL_SCOPE }),
  // Identifier-first entry step (auth/security review §2.2) -- resolves an email or mobile
  // number to what the frontend should show next, without a raw exists boolean. See
  // AuthService.identify on the backend: nextAction is 'PASSWORD' | 'GOOGLE' | 'APPLE' for an
  // existing account, or 'CONTINUE' when there isn't one yet.
  identify: (identifier: string) =>
    api.post<{ nextAction: string }>('/auth/identify', { identifier }).then((r) => r.data),
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
  // BH-012: no body token, on either call. The refresh token travels as the HttpOnly cookie and
  // nothing in this app can read it -- which is the entire point of the cookie. The backend
  // already prefers the cookie and treats the body as an optional fallback for native clients
  // with no cookie jar (RefreshTokenCookie.resolve, @RequestBody(required = false)), so this needs
  // no server change; mobile keeps sending a body from its own client and is unaffected.
  refresh: () =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh').then((r) => r.data.data),
  logout: () =>
    api.post<{ message: string }>('/auth/logout').then((r) => r.data),
  // token is the reactivation token AUTH_ACCOUNT_DEACTIVATED's error details carry -- see
  // AuthContext.reactivate and ReactivateAccountPrompt.tsx. Returns the same shape as login.
  reactivate: (token: string) =>
    api.post<AuthResponseDto>('/auth/reactivate', { token }),
  // idToken is the credential Google Identity Services hands back after the user picks an
  // account -- verified server-side (GoogleIdTokenVerifierService), never trusted as-is. Serves
  // both registration and login: the backend auto-links to an existing account sharing the same
  // Google-verified email, or creates one, and returns the same AuthResponseDto shape either way.
  google: (idToken: string) =>
    api.post<AuthResponseDto>('/auth/google', { idToken }),
  // token is the raw verification token from a /verify-email?token=... link (register(), or a
  // fresh one loginWithGoogle sends when it finds a matching but not-yet-verified account -- see
  // VerifyEmail.tsx). Not authenticated: the token itself is the proof.
  verifyEmail: (token: string) =>
    api.post<{ message: string }>('/auth/verify-email', { token }).then((r) => r.data),
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

// Mirrors TransactionDto.CreateRequest -- see TransactionService.create() for what the backend
// actually does with each field: merchant is derived from description server-side
// (CategoryRules.extractMerchant), never sent; a null/omitted categoryName takes the engine's own
// auto-categorization path instead of a manual one. accountId is required (a transaction always
// belongs to an account the caller owns -- see getOwnedAccount's own ownership check), unlike
// UpdateTransactionPayload below, which deliberately excludes it.
export interface CreateTransactionPayload {
  accountId: string;
  categoryName?: string | null;
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  tags?: string[];
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

// Mirrors TransactionExplanationDto. Fetched on demand (the "Why this category?" panel), not
// as part of every list row -- see that DTO's own doc comment for why: every field on it already
// existed on Transaction before this endpoint did, this just reads it back out.
export interface TransactionExplanation {
  decisionSource: string;
  summary: string;
  evidence: string[];
}

export const transactionsApi = {
  search: (filters: TransactionFilters) =>
    api.get<PagedResponse<Transaction>>('/transactions', { params: filters }).then((r) => r.data),
  needsReview: () => api.get<Transaction[]>('/transactions/needs-review').then((r) => r.data),
  explanation: (id: string) =>
    api.get<TransactionExplanation>(`/transactions/${id}/explanation`).then((r) => r.data),
  create: (body: CreateTransactionPayload) => api.post<Transaction>('/transactions', body).then((r) => r.data),
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

export interface ConfirmedRowPayload {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  category: string;
  include: boolean;
  categorySource: string;
  ruleId: string | null;
  /** What the engine guessed. */
  likelyDuplicate: boolean;
  /**
   * What the USER answered on the duplicate review screen — true only for a flagged row they
   * looked at and chose "Import anyway".
   *
   * Separate from `likelyDuplicate` because nothing on the server can reconstruct it: reconciliation
   * sees two rows with the same date, amount and description and cannot tell "the same statement
   * uploaded twice" from "two metro fares on one day". Untold, it marks the row a duplicate and
   * every spend total drops it. Optional so the multi-account path, which has no review screen yet,
   * keeps its existing shape.
   */
  confirmedNotDuplicate?: boolean;
}

/**
 * What `ImportDto.NewAccountRequest` accepts — all nineteen fields, not the ten this used to name.
 *
 * The nine below were absent from this type while the single-account path sent them anyway: the
 * payload was built into a `const` first, so TypeScript's excess-property check (which only fires on
 * a fresh object literal assigned straight to a typed target) never saw them. The type could
 * therefore describe two thirds of the request and still typecheck both call sites — including the
 * multi-account one, which omitted all nine and created a composite statement's fixed-deposit
 * section as an empty savings account.
 *
 * Declaring them is what makes that omission a type error rather than a silent difference between
 * two hand-rolled payloads. `toNewAccountPayload` in `lib/newAccountPayload.ts` is the only
 * supported way to build one, for the same reason `beginReview` is the only way to start a review.
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

  // What the section IS, so the server can route it — a term deposit becomes an Investment rather
  // than an empty savings account. Null when the engine was unsure: the type the user picked on the
  // form is then the answer, and re-asserting a guess here would override their correction.
  detectedProduct?: string | null;
  // Opaque; lets a re-import recognise a product already held instead of duplicating it.
  productIdentityHash?: string | null;

  // Server-detected, displayed read-only, echoed back unchanged — what makes a deposit a deposit
  // rather than a name and a balance. All seven nullable: a field irrelevant to the product's type
  // is simply never populated.
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
  // Echoed back from DetectedAccountInfo.statementPeriodStart/End, same round-trip as the
  // opening/closing balance fields above -- see ConfirmRequest's own doc comment on the backend
  // for the bug this closes (persistSection used to silently re-derive the period from the
  // confirmed rows' own date range instead of the printed period shown on the review screen).
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  // Only meaningful to confirmReimport, for a statement whose stored bytes are a password-protected
  // PDF -- see ConfirmRequest's own doc comment on the backend. Every other confirm path ignores it.
  password?: string;
}

// One account's worth of reviewed rows within a MultiAccountConfirmPayload -- same shape as
// ConfirmPayload minus sessionId (shared once at the top level instead of repeated per section).
export interface SectionConfirmPayload {
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
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

// Premium Import Reliability v1, §2.1 -- mirrors backend ImportDto.ImportFailureSummaryDto
// exactly, including its deliberate omission of failureDetail (admin/debug-only, can carry a
// fragment of the document that defeated the parser). failureCode is a lookup key for
// importFailureMessages.ts, not a message to show verbatim.
export interface ImportFailureSummary {
  reference: string;
  fileName: string;
  failureCode: string | null;
  createdAt: string;
}

export interface StagingResult {
  rows: StagedRow[];
  totalParsed: number;
  flaggedDuplicates: number;
  detectedAccount: DetectedAccountInfo;
  unparseableRows: UnparseableRow[];
  // Optional rather than required: absent means an older backend that predates verification,
  // which is the same "not checked" state as an explicit null and must not read as a failure.
  verification?: VerificationReport | null;
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
  // "Your recent failed imports" -- Premium Import Reliability v1, §2.1. A document that never got
  // far enough to become an ImportSession (no header found, zero transactions, a scanned PDF)
  // previously left no trace its owner could see again; this is that trace, read back.
  listFailures: () => api.get<ImportFailureSummary[]>('/import/failures').then((r) => r.data),
};

/**
 * The asynchronous upload path: hand the file over, watch it, review it when it lands.
 *
 * Runs beside `importApi.stageCsv`/`stagePdf` rather than replacing them. Those return
 * `200 {sessionId, staging}` and both this app and the mobile app read those fields, so changing
 * them would be two entries on the breaking list in `docs/engineering/api-compatibility-policy.md`.
 * Adding endpoints is explicitly non-breaking, which is why there are two paths to the same review
 * screen and only one of them is new.
 *
 * `availability` is asked BEFORE the upload, not discovered from it. The queue is opt-in per
 * deployment, and the multipart body is consumed before the handler runs — so an upload sent to a
 * deployment without the queue would cross the network in full, come back 503, and have to cross it
 * again on the synchronous path. One small GET replaces that.
 */
export interface ImportJobProgress {
  jobId: string;
  // Premium Import Reliability v1, §3.2 -- the import detail page's only source for what was
  // uploaded; nothing else in this response names it.
  fileName: string;
  status: 'QUEUED' | 'PARSING' | 'ANALYZING' | 'DEDUPING' | 'IMPORTING' | 'LEARNING'
    | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  // Sprint 4 item 20a's five-state mapping, additive alongside `status` (unchanged, still needed
  // for the timeline UI's per-stage granularity) -- for a caller that wants the collapsed
  // "processing / completed / action required / failed / cancelled" view without re-deriving it.
  userStatus: 'PROCESSING' | 'COMPLETED' | 'ACTION_REQUIRED' | 'FAILED' | 'CANCELLED';
  // Null while the statement is still being read — deliberately not 0, which would be
  // indistinguishable from an empty file and would render as a stuck "0 of 0".
  rowsTotal: number | null;
  rowsProcessed: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  // Where the staged rows ended up. Populated on COMPLETED; this is what the review screen loads.
  importSessionId: string | null;
  // Only once the job has actually FAILED. A job that failed once and is retrying is the system
  // working, so a transient error is deliberately not surfaced mid-flight.
  error: string | null;
  correlationId: string | null;
}

/** One stage's transition, for the import timeline (Premium Import Reliability v1, §3.1).
 *  `attempt` is carried on every row rather than collapsed to "latest attempt only" -- a job that
 *  failed once and auto-retried successfully is worth showing, not hidden. */
export interface ImportTimelineStage {
  stage: ImportJobProgress['status'];
  attempt: number;
  outcome: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  startedAt: string | null;
  endedAt: string | null;
  durationMs: number | null;
}

/** The full timeline for one job. `failureCode` is the wire code (e.g. `"IMPORT_001"`) -- the same
 *  vocabulary `importFailureMessage` already turns into a curated sentence -- and is only populated
 *  once the job has actually FAILED, same rule `ImportJobProgress.error` follows. */
export interface ImportJobTimeline {
  jobId: string;
  status: ImportJobProgress['status'];
  userStatus: ImportJobProgress['userStatus'];
  failureCode: string | null;
  stages: ImportTimelineStage[];
}

export const importJobsApi = {
  availability: () =>
    api.get<{ asyncImportAvailable: boolean }>('/import/jobs/availability').then((r) => r.data),
  submit: (file: File, onProgress?: ProgressCallback) => {
    const form = new FormData();
    form.append('file', file);
    return api
      .post<{ jobId: string; statusUrl: string }>('/import/jobs', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        ...toUploadProgressConfig(onProgress),
      })
      .then((r) => r.data);
  },
  progress: (jobId: string) =>
    api.get<ImportJobProgress>(`/import/jobs/${jobId}`).then((r) => r.data),
  timeline: (jobId: string) =>
    api.get<ImportJobTimeline>(`/import/jobs/${jobId}/timeline`).then((r) => r.data),
  recent: (limit = 20) =>
    api.get<ImportJobProgress[]>(`/import/jobs?limit=${limit}`).then((r) => r.data),
  // POST, not DELETE: this ends the work and keeps the row, because a cancelled import is part of
  // the user's history. Returns the job's new state so the caller renders from the response instead
  // of racing its own next poll.
  cancel: (jobId: string) =>
    api.post<ImportJobProgress>(`/import/jobs/${jobId}/cancel`).then((r) => r.data),
};

export const statementImportsApi = {
  listGroupedByAccount: () => api.get<AccountStatementGroup[]>('/statement-imports').then((r) => r.data),
  detail: (id: string) => api.get<StatementSummary>(`/statement-imports/${id}`).then((r) => r.data),
  transactions: (id: string) => api.get<Transaction[]>(`/statement-imports/${id}/transactions`).then((r) => r.data),
  // A plain <a href> can't carry the Bearer token, so this goes through the same authenticated
  // axios instance as everything else and triggers the browser download client-side instead.
  downloadFile: async (id: string, fileName: string) => {
    try {
      const res = await api.get(`/statement-imports/${id}/file`, { responseType: 'blob' });
      downloadBlob(res.data as Blob, fileName);
    } catch (err) {
      // responseType: 'blob' applies to ERROR responses too, so on a 4xx/5xx error.response.data
      // is a Blob rather than the parsed {message, errorCode} envelope. client.ts's interceptor
      // tests error.response?.data?.message, a Blob has no such property, the normalising branch
      // is skipped, and the caller gets an error with no readable detail -- for a path whose
      // backend failures are specific and actionable ("Statement ... is in object storage, but no
      // storage provider is configured"). Reading the blob back as text restores the envelope the
      // rest of the app expects.
      throw await withBlobErrorMessage(err);
    }
  },
  // `password` is only ever needed for a statement originally uploaded as a protected PDF: the
  // stored bytes are still encrypted, and the password used at upload is deliberately never
  // persisted, so it has to be supplied again here. In the body, never the URL -- a document
  // password in a query string is captured by access logs, proxy logs and browser history.
  reimport: (id: string, password?: string) =>
    api.post<ReimportResult>(`/statement-imports/${id}/reimport`, password ? { password } : {}).then((r) => r.data),
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
  journey: () => api.get<FinancialJourney>('/dashboard/journey').then((r) => r.data),
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
  // Bug fix (review): this claimed non-nullable, which is wrong for a Google Sign-In account
  // (see AuthService.createGoogleUserRecord's own doc comment -- phoneNumber is left null there
  // by design) and is exactly the kind of type dishonesty that let VerifyPhone.tsx pass a real
  // null straight into Firebase's SDK with no compiler warning anywhere in between.
  phoneNumber: string | null;
  phoneVerified: boolean;
  createdAt: string;
  // Null until the account's password has been changed at least once -- never a guessed
  // fallback date (see the backend User.passwordChangedAt's own doc comment).
  passwordChangedAt: string | null;
  // 'PASSWORD' or 'GOOGLE' -- see the backend User.signInMethod's own doc comment. A 'GOOGLE'
  // account's passwordHash is a random value nobody, including the user, ever knows, so every
  // "re-enter your current password" modal (ChangePasswordModal, DeleteAccountModal,
  // DeactivateAccountModal, ExportDataModal) reads this to decide whether to render a password
  // field or a GoogleSignInButton instead.
  signInMethod: 'PASSWORD' | 'GOOGLE';
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
  // Exactly one of the two is required -- currentPassword for an ordinary account, googleIdToken
  // (a fresh Google Identity Services credential) for one created via Sign in with Google. See
  // the backend's GoogleReauthVerifier, which is what actually enforces that.
  start: (currentPassword: string | null, googleIdToken: string | null) =>
    api.post<{ sessionId: string; phoneNumber: string; maskedPhone: string }>(
      '/users/me/password-change/start', { currentPassword, googleIdToken }
    ).then((r) => r.data),
  verifyOtp: (sessionId: string, firebaseIdToken: string) =>
    api.post<{ message: string }>(
      '/users/me/password-change/verify-otp', { sessionId, firebaseIdToken }
    ).then((r) => r.data),
  // currentRefreshToken lets the backend positively identify (and exclude) this device from
  // revocation when signOutOtherDevices is true -- an access token alone doesn't carry enough
  // information to know which refresh token belongs to this browser tab.
  // BH-012: no currentRefreshToken. This browser can no longer read its own refresh token (it is
  // HttpOnly now) and does not need to -- the backend identifies "this device" from the access
  // token's sid claim, which names the session directly and survives rotation. The field is
  // deprecated and ignored server-side; mobile still sends it and is unaffected.
  complete: (sessionId: string, newPassword: string, signOutOtherDevices: boolean) =>
    api.post<{ message: string; otherDevicesSignedOut: boolean }>(
      '/users/me/password-change/complete', { sessionId, newPassword, signOutOtherDevices }
    ).then((r) => r.data),
};

// The OTP-gated Change Phone Number flow -- see VerifyPhone.tsx's own "Change Number" step, and
// PhoneChangeService on the backend for the full start -> verify-otp -> complete state machine.
// Reachable by an unverified user (see PhoneVerificationFilter's own PHONE_CHANGE_ENDPOINTS
// carve-out) unlike passwordChangeApi above -- this IS the recovery path for someone who cannot
// verify at all. No currentPassword step first, unlike passwordChangeApi.start: the caller is
// already authenticated, and the OTP itself (proving control of the NEW number) is the entire
// proof this flow needs.
export const phoneChangeApi = {
  start: (newPhoneNumber: string) =>
    api.post<{ sessionId: string; maskedPhone: string }>(
      '/users/me/phone-change/start', { newPhoneNumber }
    ).then((r) => r.data),
  verifyOtp: (sessionId: string, firebaseIdToken: string) =>
    api.post<{ message: string }>(
      '/users/me/phone-change/verify-otp', { sessionId, firebaseIdToken }
    ).then((r) => r.data),
  complete: (sessionId: string) =>
    api.post<{ message: string; phoneNumber: string }>(
      '/users/me/phone-change/complete', { sessionId }
    ).then((r) => r.data),
};

// The step-up-gated Change Email flow -- see EmailChangeService on the backend for the full
// start -> verify -> complete state machine. Unlike phoneChangeApi, DOES have a "prove you still
// are who you say you are" first step (currentPassword/googleIdToken/appleIdToken, same shape as
// passwordChangeApi.start): email is the account's password-reset delivery channel, so
// authorizing a change to it on phone-change's lower bar would be worse, not better. Unlike
// verifyOtp above, verify here proves control of the new address via a link the backend emailed
// to it (see VerifyEmailChange.tsx) rather than an in-app Firebase OTP -- appleIdToken is always
// null from this web client (Apple Sign-In has no web frontend counterpart here, see
// GoogleReauthPrompt's own doc comment).
export const emailChangeApi = {
  start: (currentPassword: string | null, googleIdToken: string | null, appleIdToken: string | null, newEmail: string) =>
    api.post<{ sessionId: string; devVerifyLink: string | null }>(
      '/users/me/email-change/start', { currentPassword, googleIdToken, appleIdToken, newEmail }
    ).then((r) => r.data),
  verify: (sessionId: string, token: string) =>
    api.post<{ message: string }>(
      '/users/me/email-change/verify', { sessionId, token }
    ).then((r) => r.data),
  complete: (sessionId: string) =>
    api.post<{ message: string; email: string }>(
      '/users/me/email-change/complete', { sessionId }
    ).then((r) => r.data),
};

// The self-service account lifecycle -- see UserAccountLifecycleService on the backend for
// deactivate (today) and delete-request/purge (Phase B, to follow).
export const accountLifecycleApi = {
  // Exactly one of currentPassword/googleIdToken is required -- see passwordChangeApi.start's
  // identical shape and the backend's GoogleReauthVerifier.
  deactivate: (currentPassword: string | null, googleIdToken: string | null, reason: string, note?: string) =>
    api.post<{ message: string }>('/users/me/account/deactivate', { currentPassword, googleIdToken, reason, note }).then((r) => r.data),
  // sessionId proves current-password+OTP -- see PasswordChangeService.consumeForAccountDeletion,
  // reused via the same DELETION_CONFIRMED-gated session DeleteAccountModal builds up through
  // passwordChangeApi.start/verifyOtp.
  deleteAccount: (sessionId: string) =>
    api.post<{ message: string }>('/users/me/account/delete', { sessionId }).then((r) => r.data),
  // Phase C (Download My Data). POST with the password in the body -- responseType: 'blob' since
  // the response is a streamed ZIP, not JSON (see UserController.exportData/DataExportService on
  // the backend). The filename mirrors the backend's own "finora-data-export-<date>.zip" pattern
  // rather than being read back out of Content-Disposition -- nothing else in this codebase parses
  // that header either (statementImportsApi.downloadFile above takes its filename from the caller
  // instead), and the two dates can only disagree by the moment the request straddles midnight.
  exportData: async (currentPassword: string | null, googleIdToken: string | null) => {
    try {
      const res = await api.post('/users/me/data-export', { currentPassword, googleIdToken }, { responseType: 'blob' });
      downloadBlob(res.data as Blob, `finora-data-export-${new Date().toISOString().slice(0, 10)}.zip`);
    } catch (err) {
      // responseType: 'blob' applies to error responses too -- see withBlobErrorMessage's own doc
      // comment above (statementImportsApi.downloadFile hits the identical issue).
      throw await withBlobErrorMessage(err);
    }
  },
};

// Self-service view of the caller's own active refresh-token sessions -- backs Settings.tsx's
// Active Sessions list under Security. Mirrors the backend's DeviceSessionDto exactly; browser/
// device/lastSeenIp are best-effort labels captured from whichever request last issued/rotated
// that token, not a durable per-device fingerprint (see RefreshToken's own doc comment on the
// backend), so any of them can legitimately be null.
export interface DeviceSession {
  // The current refresh TOKEN's id -- changes on every rotation. Still what revoke() takes.
  id: string;
  // The SESSION's id, stable across rotations. Not used by the UI today; exposed because
  // anything scoped to the sign-in rather than the token (device naming, trusted devices) keys
  // off it.
  sessionId: string;
  // Whether this is the session making the request. Decided server-side from the caller's own
  // access token, so the client neither stores nor sends a session id.
  current: boolean;
  browser: string | null;
  device: string | null;
  lastSeenIp: string | null;
  lastSeenAt: string | null;
  createdAt: string;
  expiresAt: string;
  // When the user signed in ON THIS DEVICE. Distinct from createdAt, which rotation resets to the
  // time of the most recent token refresh -- roughly every fifteen minutes, so it says nothing
  // about how old the session is.
  sessionStartedAt: string;
  // sessionStartedAt + the absolute session cap, computed server-side so the countdown does not
  // depend on the device's own clock being correct. Null when the cap is disabled.
  sessionExpiresAt: string | null;
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

// --- Gmail Transaction Sync (C5.4) ---
//
// Mirrors GmailConnectionStatusDto exactly. The connect/callback/status/disconnect endpoints have
// existed on the backend since Phase B; this is the first frontend caller for any of them --
// there was no "Connect Gmail" button anywhere until now, so wiring the connection flow itself is
// part of "the minimum needed to make C5 usable", not just the review queue.

export interface GmailConnectionStatus {
  connected: boolean;
  status: string | null;
  needsReconnect: boolean;
  googleEmail: string | null;
  grantedScopes: string[];
  connectedAt: string | null;
  lastSyncedAt: string | null;
  lastDiscoveryAt: string | null;
  transactionsFound: number;
  needsReview: number;
  available: boolean;
}

// Mirrors GmailReviewItemDto. sessionId is what approve()/reject() take -- there is no separate
// "receipt id"; a Gmail-sourced ImportSession IS the receipt (GmailStagingBridge stages exactly
// one row per session), see GmailReviewService's own doc comment.
export interface GmailReviewItem {
  sessionId: string;
  merchant: string;
  merchantDomain: string;
  amount: number;
  date: string;
  category: string;
  confidence: number | null;
  stagedAt: string;
  reasoning: string;
}

export const gmailApi = {
  status: () => api.get<GmailConnectionStatus>('/integrations/google/gmail/status').then((r) => r.data),
  connect: () =>
    api.post<{ authorizationUrl: string }>('/integrations/google/gmail/connect').then((r) => r.data),
  disconnect: () => api.delete('/integrations/google/gmail/connection'),
  syncNow: () => api.post('/integrations/google/gmail/sync-now'),
  reviewQueue: () =>
    api.get<GmailReviewItem[]>('/integrations/google/gmail/review-queue').then((r) => r.data),
  approve: (sessionId: string, category?: string) =>
    api.post(`/integrations/google/gmail/review/${sessionId}/approve`, category ? { category } : {}),
  reject: (sessionId: string) => api.post(`/integrations/google/gmail/review/${sessionId}/reject`),
};

// D-28 PR4-A. What PremiumFeatureGate reads -- the current user's own plan and entitlement map.
// Mirrors backend BillingDtos.EntitlementsDto exactly.
export interface EntitlementsDto {
  planCode: string | null;
  planName: string | null;
  features: Record<string, boolean>;
}

export const entitlementsApi = {
  mine: () => api.get<EntitlementsDto>('/entitlements').then((r) => r.data),
};

// D-28 PR4-B. The user's own billing history (proposal §3.4) -- empty for everyone today, since
// no payment gateway exists yet (§10). Mirrors backend BillingDtos.BillingHistoryEntryDto exactly.
export interface BillingHistoryEntry {
  id: string;
  amount: number;
  currency: string;
  provider: string | null;
  status: string;
  createdAt: string;
}

export const billingApi = {
  history: () => api.get<BillingHistoryEntry[]>('/billing/history').then((r) => r.data),
};

// D-28 PR4-C. The referral program (proposal §4) -- mirrors backend ReferralDtos exactly.
export interface MyReferralEntry {
  referralId: string;
  referredUserFullName: string | null;
  status: string;
  reward: number | null;
  createdAt: string;
}

export interface MyReferralsDto {
  referrals: MyReferralEntry[];
  walletBalance: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};
