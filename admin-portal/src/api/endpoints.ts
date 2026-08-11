import { api, rawApi, type ApiEnvelope } from './client';
import type {

  AccountDto, AdminUpdateUserRequest, AuditLogDto, BankDto, CategoryConfidencePoint,
  CreateAccountRequest, CreateBankRequest, CreateRelationshipRequest,
  CreateRuleRequest, CreateUserRequest, FeatureFlagDto, LearningGrowthPoint, LearningPlatformStatsDto, LearningSummaryDto,
  LearningTimelineEntry,
  MeAccessDto, MerchantDto, MerchantMergeRequest, MerchantStatDto,
  MerchantUpdateRequest, OperationalDashboardDto, PagedResponse, PermissionDto, PlatformAnalyticsDto,
  PlatformDiagnosticsDto, PlatformSettingsDto, PlatformStatsDto, ReconciliationStatsDto, RecentImportDto,
  RelationshipDto, RelationshipMergeRequest, RoleDto, RuleDto,
  SearchResultDto, SystemHealthDto,
  TestRuleRequest, TestRuleResult, TopCategoryPoint, TopMerchantPoint, TransactionDto, TrendPoint,
  UpdateBankRequest, UpdateFeatureFlagRequest,
  UpdatePlatformSettingsRequest, UpdateRelationshipRequest,
  UpdateRuleRequest, UserDetailDto, UserSummaryDto, WorkspaceSummaryDto,
  StatementAnalysisDto,
  StatementAnalysisDetailDto,
  StatementAnalysisSummaryDto,
  LearningQueueEvent, LearningQueueSummary,
  MerchantReviewItem,
  LayoutSummary,
  UnknownHeaderSummary,
  LayoutTimelinePoint,
  LayoutEvidenceReport,
  ImportTrace,
} from '../types';

// Which portal this account belongs to. The same person may hold a USER account and an ADMIN
// account under one email and one mobile number, so login and password reset have to say which
// one they mean. Not an authorization signal -- what an account may do is decided by its roles.
const PORTAL_SCOPE = 'ADMIN';


// Auth reuses the exact same /auth/* endpoints the user app calls -- there is only one backend,
// one user table, one login flow. What makes this "an admin session" is entirely client-side
// (AdminAuthContext gating entry on meAccessApi.get() returning at least one admin permission),
// not a separate auth mechanism.
export const authApi = {
  login: (identifier: string, password: string) =>
    api.post<{ token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }>(
      '/auth/login', { identifier, password, scope: PORTAL_SCOPE }).then((r) => r.data),
  // BH-012: no body token, on either call. The refresh token travels as the HttpOnly cookie and
  // this app cannot read it to put one in a request body even if it wanted to -- withCredentials
  // on `rawApi`/`api` is what gets it attached. RefreshTokenCookie.resolve() on the backend would
  // still accept a body token as a fallback (mobile has no cookie jar and needs it), but there is
  // nothing here to send. Mirrors frontend/src/api/endpoints.ts's identical authApi.refresh().
  refresh: () =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh').then((r) => r.data.data),
  logout: () =>
    api.post('/auth/logout'),
  // Same /auth/forgot-password and /auth/reset-password endpoints the user app (frontend/)
  // already calls -- there's no separate admin-specific password reset mechanism, just one
  // shared implementation, same reasoning as login() above and the phone verification endpoints.
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email, scope: PORTAL_SCOPE }).then((r) => r.data),
  // Second factor for password reset -- the reset token alone (proof of email access) is no
  // longer sufficient; a phone OTP via Firebase Phone Authentication (proof of phone access) is
  // required too. The backend never sends the OTP itself, only reveals the real phone number so
  // this app can hand it to Firebase directly -- same as frontend/'s authApi.resolveResetPasswordPhone.
  resolveResetPasswordPhone: (token: string) =>
    api.post<{ phoneNumber: string }>('/auth/reset-password/phone', { token }).then((r) => r.data),
  resetPassword: (token: string, firebaseIdToken: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, firebaseIdToken, newPassword }).then((r) => r.data),
};

export const meApi = {
  access: () => api.get<MeAccessDto>('/users/me/access').then((r) => r.data),
};

// The current admin's own settings -- only phoneNumber is used here (VerifyPhone.tsx needs the
// real number to hand to Firebase's signInWithPhoneNumber()), same /users/me endpoint the user
// app (frontend/) calls for the same reason. Not gated behind any admin permission -- it's just
// "my own account," same as meApi.access() above.
export const userApi = {
  get: () => api.get<{ phoneNumber: string }>('/users/me').then((r) => r.data),
};

// Just one endpoint now -- there's no backend-triggered "send" step (Firebase's own client SDK
// sends the OTP directly), only verifying the Firebase ID token that results from it. Same
// /phone/verify endpoint the user app (frontend/) calls; see
// docs/adr/0001-administrator-verification-strategy.md for why this reuses the existing flow
// rather than the admin portal growing its own parallel one.
export const phoneApi = {
  verify: (firebaseIdToken: string) =>
    api.post<{ message: string }>('/phone/verify', { firebaseIdToken }).then((r) => r.data),
};

export interface UserListFilters {
  q?: string;
  status?: string;
  page?: number;
  size?: number;
}

export const adminUsersApi = {
  list: (filters: UserListFilters) =>
    api.get<PagedResponse<UserSummaryDto>>('/admin/users', { params: filters }).then((r) => r.data),
  get: (id: string) =>
    api.get<UserDetailDto>(`/admin/users/${id}`).then((r) => r.data),
  suspend: (id: string) =>
    api.post<UserSummaryDto>(`/admin/users/${id}/suspend`).then((r) => r.data),
  reactivate: (id: string) =>
    api.post<UserSummaryDto>(`/admin/users/${id}/reactivate`).then((r) => r.data),
  create: (request: CreateUserRequest) =>
    api.post<UserSummaryDto>('/admin/users', request).then((r) => r.data),
  update: (id: string, request: AdminUpdateUserRequest) =>
    api.put<UserSummaryDto>(`/admin/users/${id}`, request).then((r) => r.data),
};

export const adminRolesApi = {
  listRoles: () => api.get<RoleDto[]>('/admin/roles').then((r) => r.data),
  createRole: (name: string, description: string) =>
    api.post<RoleDto>('/admin/roles', { name, description }).then((r) => r.data),
  updateRole: (id: string, description: string) =>
    api.put<RoleDto>(`/admin/roles/${id}`, { description }).then((r) => r.data),
  deleteRole: (id: string) => api.delete(`/admin/roles/${id}`),
  listPermissions: () => api.get<PermissionDto[]>('/admin/permissions').then((r) => r.data),
  createPermission: (name: string, description: string) =>
    api.post<PermissionDto>('/admin/permissions', { name, description }).then((r) => r.data),
  updatePermission: (id: string, description: string) =>
    api.put<PermissionDto>(`/admin/permissions/${id}`, { description }).then((r) => r.data),
  deletePermission: (id: string) => api.delete(`/admin/permissions/${id}`),
  grantPermission: (roleId: string, permissionId: string) =>
    api.post<RoleDto>(`/admin/roles/${roleId}/permissions/${permissionId}`).then((r) => r.data),
  revokePermission: (roleId: string, permissionId: string) =>
    api.delete<RoleDto>(`/admin/roles/${roleId}/permissions/${permissionId}`).then((r) => r.data),
  assignRole: (userId: string, roleName: string) =>
    api.post<RoleDto>(`/admin/users/${userId}/roles/${roleName}`).then((r) => r.data),
  revokeRole: (userId: string, roleName: string) =>
    api.delete(`/admin/users/${userId}/roles/${roleName}`),
};

// The full bank registry (built-in ~40 + any custom banks from adminBanksApi) -- used as the bank
// picker when an admin creates/edits an account on a user's behalf. Same public /api/v1/banks
// endpoint the user-facing app's own account setup uses (see backend BankController), just called
// from this app's authenticated axios instance instead.
export const banksApi = {
  search: (q?: string) => api.get<BankDto[]>('/banks', { params: q ? { q } : undefined }).then((r) => r.data),
};

export const adminBanksApi = {
  list: () => api.get<BankDto[]>('/admin/banks').then((r) => r.data),
  create: (request: CreateBankRequest) =>
    api.post<BankDto>('/admin/banks', request).then((r) => r.data),
  update: (id: string, request: UpdateBankRequest) =>
    api.put<BankDto>(`/admin/banks/${id}`, request).then((r) => r.data),
  delete: (id: string) => api.delete(`/admin/banks/${id}`),
  // Admin Portal Phase 4 -- backs the Banks page's EntityDrawer Audit tab. Empty array for a bank
  // with no recorded history (a built-in bank, or a custom bank predating this endpoint), never
  // an error -- see AdminBankController.audit's doc comment.
  audit: (id: string) => api.get<AuditLogDto[]>(`/admin/banks/${id}/audit`).then((r) => r.data),
};

export const adminAccountsApi = {
  list: (userId: string) =>
    api.get<AccountDto[]>(`/admin/users/${userId}/accounts`).then((r) => r.data),
  create: (userId: string, request: CreateAccountRequest) =>
    api.post<AccountDto>(`/admin/users/${userId}/accounts`, request).then((r) => r.data),
  update: (userId: string, accountId: string, request: CreateAccountRequest) =>
    api.put<AccountDto>(`/admin/users/${userId}/accounts/${accountId}`, request).then((r) => r.data),
  delete: (userId: string, accountId: string) =>
    api.delete(`/admin/users/${userId}/accounts/${accountId}`),
};

export const adminTransactionsApi = {
  list: (userId: string) =>
    api.get<TransactionDto[]>(`/admin/users/${userId}/transactions`).then((r) => r.data),
  delete: (userId: string, transactionId: string) =>
    api.delete(`/admin/users/${userId}/transactions/${transactionId}`),
};

export const adminRulesApi = {
  list: () => api.get<RuleDto[]>('/admin/rules').then((r) => r.data),
  create: (request: CreateRuleRequest) =>
    api.post<RuleDto>('/admin/rules', request).then((r) => r.data),
  update: (id: string, request: UpdateRuleRequest) =>
    api.put<RuleDto>(`/admin/rules/${id}`, request).then((r) => r.data),
  delete: (id: string) => api.delete(`/admin/rules/${id}`),
  // Dry-run against sample fields -- never creates or persists a rule. See
  // AdminRuleController's /test endpoint doc comment.
  test: (request: TestRuleRequest) =>
    api.post<TestRuleResult>('/admin/rules/test', request).then((r) => r.data),
};

/** Admin, support-assisted personal rule management for a specific user -- restores the rule-
 *  authoring capability that was removed from the User Portal (AskOnceCard/updateCategory's
 *  automatic per-merchant learning covers the common case; this covers the richer
 *  field/operator/action combinations only an explicit rule can express). Reuses
 *  AdminUserRuleController, which itself reuses the same RuleService the old self-service page did. */
export const adminUserRulesApi = {
  list: (userId: string) => api.get<RuleDto[]>(`/admin/users/${userId}/rules`).then((r) => r.data),
  create: (userId: string, request: CreateRuleRequest) =>
    api.post<RuleDto>(`/admin/users/${userId}/rules`, request).then((r) => r.data),
  update: (userId: string, id: string, request: UpdateRuleRequest) =>
    api.put<RuleDto>(`/admin/users/${userId}/rules/${id}`, request).then((r) => r.data),
  delete: (userId: string, id: string) => api.delete(`/admin/users/${userId}/rules/${id}`),
};

export const platformSettingsApi = {
  get: () => api.get<PlatformSettingsDto>('/admin/settings').then((r) => r.data),
  update: (request: UpdatePlatformSettingsRequest) =>
    api.put<PlatformSettingsDto>('/admin/settings', request).then((r) => r.data),
};

// Admin Portal Phase 8 -- reuses the SYSTEM_SETTINGS gate, same reasoning as adminSystemApi above:
// flipping a platform-wide flag is the same class of internal operational configuration.
export const adminFeatureFlagsApi = {
  list: () => api.get<FeatureFlagDto[]>('/admin/feature-flags').then((r) => r.data),
  update: (id: string, request: UpdateFeatureFlagRequest) =>
    api.put<FeatureFlagDto>(`/admin/feature-flags/${id}`, request).then((r) => r.data),
};

export interface AuditLogFilters {
  q?: string;
  dateFrom?: string;
  dateTo?: string;
  sortDir?: 'asc' | 'desc';
}

export const adminAuditApi = {
  forUser: (userId: string) =>
    api.get<AuditLogDto[]>(`/admin/users/${userId}/audit-logs`).then((r) => r.data),
  // Admin Portal Phase 5 -- q/dateFrom/dateTo/sortDir all optional (AdminController
  // .globalAuditLogs), backing the Activity Feed's FilterBar. Unset filters are simply omitted
  // from params rather than sent as empty strings, same as UserListFilters above.
  global: (page: number, size: number, filters: AuditLogFilters = {}) =>
    api.get<PagedResponse<AuditLogDto>>('/admin/audit-logs', { params: { page, size, ...filters } }).then((r) => r.data),
};

export const adminStatsApi = {
  overview: () => api.get<PlatformStatsDto>('/admin/stats/overview').then((r) => r.data),
};

// The Operational Dashboard -- "Is Finora healthy?" as one screen (AdminOperationalDashboardController).
// Reuses adminStatsApi's PLATFORM_STATS_VIEW gate rather than a new permission.
export const adminDashboardApi = {
  overview: () => api.get<OperationalDashboardDto>('/admin/dashboard/overview').then((r) => r.data),
};

export const adminSystemApi = {
  health: () => api.get<SystemHealthDto>('/admin/system/health').then((r) => r.data),
  // Admin Portal Phase 7 -- the closest honest equivalent to a background-job monitor this
  // codebase has (see RecentImportDto's doc comment for why). Shares this page's SYSTEM_SETTINGS
  // gate rather than a new permission.
  recentImports: () => api.get<RecentImportDto[]>('/admin/system/recent-imports').then((r) => r.data),
};

// Platform Diagnostics -- deliberately NOT an observability platform, see PlatformDiagnosticsDto's
// backend doc comment. Shares the SYSTEM_SETTINGS gate with adminSystemApi above.
export const adminDiagnosticsApi = {
  overview: () => api.get<PlatformDiagnosticsDto>('/admin/diagnostics').then((r) => r.data),
};

/** The merchant learning queue (WI2). Every list row already carries the correlation an operator
 *  needs -- user email, merchant and category names, statement file, session id -- so the page
 *  never has to fan out a second set of calls to render a row. */
export const adminLearningQueueApi = {
  list: (params: { status?: string; page?: number; size?: number; sortField?: string; sortDir?: string }) =>
    api.get<PagedResponse<LearningQueueEvent>>('/admin/learning-queue', { params }).then((r) => r.data),
  summary: () => api.get<LearningQueueSummary>('/admin/learning-queue/summary').then((r) => r.data),
  get: (eventId: string) =>
    api.get<LearningQueueEvent>(`/admin/learning-queue/${eventId}`).then((r) => r.data),
  retry: (eventId: string) =>
    api.post<LearningQueueEvent>(`/admin/learning-queue/${eventId}/retry`).then((r) => r.data),
  retryAll: () =>
    api.post<{ retried: number }>('/admin/learning-queue/retry-all').then((r) => r.data),
  resolve: (eventId: string, reason?: string) =>
    api.post<LearningQueueEvent>(`/admin/learning-queue/${eventId}/resolve`, { reason }).then((r) => r.data),
};

/** The Merchant Review Center (WI4). Listing crosses users; every action is scoped to the owner,
 *  which the URL shape enforces rather than merely documents -- there is no canonical merchant
 *  registry, so a cross-user merge is not expressible. */
export const adminMerchantReviewApi = {
  queue: (params: { page?: number; size?: number }) =>
    api.get<PagedResponse<MerchantReviewItem>>('/admin/merchant-review', { params }).then((r) => r.data),
  count: () => api.get<{ outstanding: number }>('/admin/merchant-review/count').then((r) => r.data),
  mergeCandidates: (userId: string, merchantId: string) =>
    api.get<MerchantReviewItem[]>(
      `/admin/merchant-review/users/${userId}/merchants/${merchantId}/merge-candidates`).then((r) => r.data),
  approve: (userId: string, merchantId: string) =>
    api.post<MerchantReviewItem>(
      `/admin/merchant-review/users/${userId}/merchants/${merchantId}/approve`).then((r) => r.data),
  approveAll: (userId: string) =>
    api.post<{ approved: number }>(`/admin/merchant-review/users/${userId}/approve-all`).then((r) => r.data),
  rename: (userId: string, merchantId: string, canonicalName: string) =>
    api.post<MerchantReviewItem>(
      `/admin/merchant-review/users/${userId}/merchants/${merchantId}/rename`, { canonicalName }).then((r) => r.data),
  merge: (userId: string, merchantId: string, survivingMerchantId: string) =>
    api.post<MerchantReviewItem>(
      `/admin/merchant-review/users/${userId}/merchants/${merchantId}/merge`, { survivingMerchantId }).then((r) => r.data),
  discard: (userId: string, merchantId: string) =>
    api.delete(`/admin/merchant-review/users/${userId}/merchants/${merchantId}`),
};

export const adminMerchantsApi = {
  platformStats: () => api.get<MerchantStatDto[]>('/admin/merchants/stats').then((r) => r.data),
};

/** Admin, support-assisted merchant management for a specific user -- AdminUserMerchantController
 *  reuses the exact same MerchantService the self-service console does, just with userId sourced
 *  from the path. confirm-category/undo/reset-learning aren't mirrored here; see that
 *  controller's class comment for why. */
export const adminUserMerchantsApi = {
  list: (userId: string) => api.get<MerchantDto[]>(`/admin/users/${userId}/merchants`).then((r) => r.data),
  update: (userId: string, merchantId: string, request: MerchantUpdateRequest) =>
    api.patch<MerchantDto>(`/admin/users/${userId}/merchants/${merchantId}`, request).then((r) => r.data),
  merge: (userId: string, merchantId: string, request: MerchantMergeRequest) =>
    api.post<MerchantDto>(`/admin/users/${userId}/merchants/${merchantId}/merge`, request).then((r) => r.data),
  // undo rolls back the single most recent learning event; resetLearning clears the merchant's
  // whole learned-category distribution. Both moved here when the self-service merchant console
  // was retired -- see AdminUserMerchantController's class comment.
  undo: (userId: string, merchantId: string) =>
    api.post<MerchantDto>(`/admin/users/${userId}/merchants/${merchantId}/undo`).then((r) => r.data),
  resetLearning: (userId: string, merchantId: string) =>
    api.post<MerchantDto>(`/admin/users/${userId}/merchants/${merchantId}/reset-learning`).then((r) => r.data),
};

/** Admin, support-assisted relationship (family/friend/own-account) tagging for a specific user
 *  -- AdminUserRelationshipController proxies the same RelationshipService the self-service
 *  endpoint used before it was retired. */
export const adminUserRelationshipsApi = {
  list: (userId: string) => api.get<RelationshipDto[]>(`/admin/users/${userId}/relationships`).then((r) => r.data),
  create: (userId: string, request: CreateRelationshipRequest) =>
    api.post<RelationshipDto>(`/admin/users/${userId}/relationships`, request).then((r) => r.data),
  update: (userId: string, id: string, request: UpdateRelationshipRequest) =>
    api.put<RelationshipDto>(`/admin/users/${userId}/relationships/${id}`, request).then((r) => r.data),
  merge: (userId: string, id: string, request: RelationshipMergeRequest) =>
    api.post<RelationshipDto>(`/admin/users/${userId}/relationships/${id}/merge`, request).then((r) => r.data),
  transactions: (userId: string, id: string) =>
    api.get<TransactionDto[]>(`/admin/users/${userId}/relationships/${id}/transactions`).then((r) => r.data),
  delete: (userId: string, id: string) => api.delete(`/admin/users/${userId}/relationships/${id}`),
};

/** Admin, read-only per-user analytics -- AdminUserAnalyticsController proxies the same
 *  AnalyticsService the self-service Analytics page used. importStatistics is deliberately absent:
 *  it stays self-service for the signed-in user's own Settings page. */
export const adminUserAnalyticsApi = {
  topMerchants: (userId: string, month?: string) =>
    api.get<TopMerchantPoint[]>(`/admin/users/${userId}/analytics/top-merchants`, { params: { month } }).then((r) => r.data),
  trend: (userId: string, month?: string) =>
    api.get<TrendPoint[]>(`/admin/users/${userId}/analytics/trend`, { params: { month } }).then((r) => r.data),
  categoryConfidence: (userId: string) =>
    api.get<CategoryConfidencePoint[]>(`/admin/users/${userId}/analytics/category-confidence`).then((r) => r.data),
  topCategories: (userId: string, month?: string) =>
    api.get<TopCategoryPoint[]>(`/admin/users/${userId}/analytics/top-categories`, { params: { month } }).then((r) => r.data),
  learningGrowth: (userId: string) =>
    api.get<LearningGrowthPoint[]>(`/admin/users/${userId}/analytics/learning-growth`).then((r) => r.data),
};

export const adminLearningApi = {
  platformStats: () => api.get<LearningPlatformStatsDto>('/admin/learning/stats').then((r) => r.data),
};

/** Admin, read-only Learning Engine visibility for a specific user -- AdminUserLearningController
 *  proxies the same MerchantLearningService.timeline()/summary() the self-service Learning
 *  Engine page used. Read-only on purpose: confirm/undo/reset live on MerchantController, not
 *  mirrored here (see AdminUserLearningController's class comment). */
export const adminUserLearningApi = {
  timeline: (userId: string) => api.get<LearningTimelineEntry[]>(`/admin/users/${userId}/learning/timeline`).then((r) => r.data),
  summary: (userId: string) => api.get<LearningSummaryDto>(`/admin/users/${userId}/learning/summary`).then((r) => r.data),
};

export const adminReconciliationApi = {
  platformStats: () => api.get<ReconciliationStatsDto>('/admin/reconciliation/stats').then((r) => r.data),
};

/** Admin, read-only Reconciliation Monitor + Workspace Health for a specific user --
 *  AdminUserWorkspaceController proxies the same WorkspaceDashboardService.summarize() the
 *  self-service Workspace Dashboard used. Reconciliation runs fully automatically (see
 *  ReconciliationService), so unlike Merchants/Rules there is nothing to manage here, only to
 *  observe. */
export const adminUserWorkspaceApi = {
  get: (userId: string) => api.get<WorkspaceSummaryDto>(`/admin/users/${userId}/workspace`).then((r) => r.data),
};

export const adminPlatformAnalyticsApi = {
  get: () => api.get<PlatformAnalyticsDto>('/admin/analytics/platform').then((r) => r.data),
};

// Global Search (Admin Portal Phase 2) -- AdminSearchController fans this one call out across
// Users/Merchants/Banks/Global Rules server-side; see that controller's class comment for why
// there's no permission gate beyond being signed in.
export const adminSearchApi = {
  search: (q: string) => api.get<SearchResultDto[]>('/admin/search', { params: { q } }).then((r) => r.data),
};

/**
 * First-run platform setup (V33__bootstrap_admin.sql / BootstrapService / SetupService). Uses
 * rawApi throughout, deliberately never the shared `api` instance -- the bootstrap account's
 * token must never be persisted to the same localStorage keys or routed through the same
 * interceptors a real admin session uses. AdminAuthContext.login()'s loadAccess() check would
 * immediately reject and clear a SYSTEM_INITIALIZE-only token, since it holds none of
 * ADMIN_PORTAL_PERMISSIONS -- this account was never meant to become "the logged-in admin," just
 * to make one API call. The bootstrap token lives only in Setup.tsx's own component state.
 */
export const setupApi = {
  status: () => rawApi.get<ApiEnvelope<{ setupRequired: boolean; installationKeyAvailable: boolean }>>('/setup/status').then((r) => r.data.data),
  loginAsBootstrap: (identifier: string, password: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string; email: string; fullName: string }>>(
      '/auth/login', { identifier, password, scope: PORTAL_SCOPE }
    ).then((r) => r.data.data),
  complete: (bootstrapToken: string, request: { email: string; password: string; fullName: string; phoneNumber: string }) =>
    rawApi.post<ApiEnvelope<null>>('/setup/complete', request, {
      headers: { Authorization: `Bearer ${bootstrapToken}` },
    }).then((r) => r.data),
};

/** Layout Studio -- the read side of the analysis evidence table. Same PLATFORM_DIAGNOSTICS_VIEW
 *  gate as adminLayoutsApi (below), different source: every upload attempt rather than only the confirmed
 *  imports, which is where the failures are. */
export const adminStatementAnalysisApi = {
  recent: (limit = 50) =>
    api.get<StatementAnalysisDto[]>('/admin/imports/analyses', { params: { limit } }).then((r) => r.data),
  summary: () =>
    api.get<StatementAnalysisSummaryDto>('/admin/imports/analyses/summary').then((r) => r.data),
  byReference: (reference: string) =>
    api.get<StatementAnalysisDetailDto>(`/admin/imports/analyses/${encodeURIComponent(reference)}`).then((r) => r.data),
};

/**
 * One import, end to end — the read behind Milestone 2's sixth success criterion.
 *
 * Two routes rather than one that inspects its argument. An analysis reference and a job id are
 * distinguishable by shape, so a single route could have served both, but a route that decides what
 * its own argument means is a route that can decide wrong — and the failure would arrive as an
 * unhelpful 404 rather than as a visibly wrong URL. Both return the same shape, so a caller holding
 * either handle never needs the other.
 *
 * `byAnalysis` is the entry point for almost every real question, because a support conversation
 * produces a reference. `byJob` is for the case that starts from the queue.
 */
export const adminImportTraceApi = {
  byAnalysis: (reference: string) =>
    api.get<ImportTrace>(`/admin/imports/traces/by-analysis/${encodeURIComponent(reference)}`)
      .then((r) => r.data),
  byJob: (jobId: string) =>
    api.get<ImportTrace>(`/admin/imports/traces/by-job/${encodeURIComponent(jobId)}`)
      .then((r) => r.data),
};

/** Runs the engine on a document and imports nothing. Separate permission (ENGINE_ANALYSIS_RUN)
 *  from the read-only reports above -- see AdminAnalysisService for why, and for how "writes
 *  nothing" is actually enforced. The password rides in the multipart body, never the URL. */
export const adminAnalysisRunApi = {
  analyze: (file: File, password?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (password) form.append('password', password);
    return api
      .post<StatementAnalysisDetailDto>('/admin/imports/analyses', form)
      .then((r) => r.data);
  },
};


/**
 * Layout Intelligence — the read side of the layout fingerprint recorded on every import since V39.
 *
 * The backend for all five of these has existed and been unreachable: no client referenced
 * `/admin/imports/layouts` at all, so the fingerprint data went from "a column nothing reads" to
 * "a service nothing calls". That is the exact gap
 * docs/engineering/layout-intelligence-proposal.md was written to close, one layer up — and the
 * comment above this block already referred to `adminLayoutsApi` as though it existed.
 *
 * Reads only. Nothing here changes parsing, and none of it feeds a decision the engine makes; it
 * exists so the evidence report can be read by a human, which is precondition 3 of the proposal's
 * §11 for ever building structural learning.
 */
export const adminLayoutsApi = {
  /** Every layout, most-used first, with its stable/unstable capability split. */
  overview: () =>
    api.get<LayoutSummary[]>('/admin/imports/layouts').then((r) => r.data),
  /** Layouts whose latest import diverges structurally from the pattern before it. */
  drifting: () =>
    api.get<LayoutSummary[]>('/admin/imports/layouts/drifting').then((r) => r.data),
  /** Headers no hint list recognises, widest-spread first — a ranked list of where the parser
   *  should improve, rather than a guess. */
  unknownHeaders: () =>
    api.get<UnknownHeaderSummary[]>('/admin/imports/layouts/unknown-headers').then((r) => r.data),
  /** One layout's imports, oldest first, flagging each point where its structure changed. */
  timeline: (fingerprint: string) =>
    api
      .get<LayoutTimelinePoint[]>(`/admin/imports/layouts/${encodeURIComponent(fingerprint)}/timeline`)
      .then((r) => r.data),
  /** First encounters versus recurrences, with a written verdict. The report that decides whether
   *  layout reuse is ever worth building — including when the answer is no. */
  evidence: () =>
    api.get<LayoutEvidenceReport>('/admin/imports/layouts/evidence').then((r) => r.data),
};
