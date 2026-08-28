import { api, rawApi, type ApiEnvelope } from './client';
import type {

  AccountDto, ActivationFunnelDto, ActivityTrendPointDto, AdminReferralSummaryDto, AdminUpdateUserRequest, AuditLogDto, BankDto, CategoryConfidencePoint,
  CreateAccountRequest, CreateBankRequest, CreateMerchantTemplateRequest, CreateRelationshipRequest,
  CreateRuleRequest, CreateUserRequest, FeatureFlagDto, GmailMerchantParserStatDto, LearningGrowthPoint, LearningPlatformStatsDto, LearningSummaryDto,
  LearningTimelineEntry,
  IntegrationsOverviewDto,
  MeAccessDto, MerchantDto, MerchantMergeRequest, MerchantStatDto, MerchantTemplateDto,
  MerchantUpdateRequest, OperationalDashboardDto, PagedResponse, PermissionDto, PlatformAnalyticsDto,
  PlatformDiagnosticsDto, PlatformSettingsDto, PlatformStatsDto, ReconciliationStatsDto, RecentImportDto,
  RelationshipDto, RelationshipMergeRequest, RoleDto, RuleDto,
  SearchResultDto, SubscriptionSummaryDto, SystemHealthDto,
  TestMerchantTemplateRequest, TestMerchantTemplateResult, TestRuleRequest, TestRuleResult,
  TopCategoryPoint, TopMerchantPoint, TransactionDto, TrendPoint,
  UpdateBankRequest, UpdateFeatureFlagRequest, UpdateMerchantTemplateRequest,
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
  ImportRowTrace,
  CustomerFailureSummary,
  ReconciliationExplorerTrace,
  InsightsExplorerTrace,
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
  // Second step of a login that came back AUTH_MFA_REQUIRED (Admin MFA UI, SEC-03) -- the
  // challenge token travels in that error's `details.mfaChallengeToken` (see client.ts's
  // interceptor). Same response shape as login() itself; AdminAuthContext.completeMfaChallenge()
  // is what applies it, exactly like completePhoneVerification() finishes login() for the
  // phone-verification branch.
  verifyMfa: (challengeToken: string, code: string) =>
    api.post<{ token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }>(
      '/auth/mfa/verify', { challengeToken, code }).then((r) => r.data),
};

export const meApi = {
  access: () => api.get<MeAccessDto>('/users/me/access').then((r) => r.data),
};

// The current admin's own settings -- same /users/me endpoint the user app (frontend/) calls for
// the same reason, not gated behind any admin permission (it's just "my own account," same as
// meApi.access() above). signInMethod added alongside phoneNumber (Admin MFA UI, SEC-03): the
// backend's UserSettingsDto has always carried it, this just widens the type this app reads it
// as -- see frontend/src/api/endpoints.ts's UserSettings for the full shape this is a subset of.
// GoogleReauthPrompt needs it to decide whether disabling MFA asks for a password or a fresh
// Google credential.
export const userApi = {
  get: () => api.get<{ phoneNumber: string; signInMethod: 'PASSWORD' | 'GOOGLE' }>('/users/me').then((r) => r.data),
};

// SEC-03: self-service TOTP MFA for the signed-in admin's own account -- see AdminMfaController's
// own doc comment on why this is gated on PORTAL_ADMIN alone rather than a specific permission
// (managing your own second factor isn't an action against another admin's data). Off entirely
// (every call 404s with AUTH_MFA_NOT_AVAILABLE) until app.admin-mfa.enabled=true server-side.
export const adminMfaApi = {
  status: () => api.get<{ enabled: boolean }>('/admin-mfa/status').then((r) => r.data),
  enroll: () => api.post<{ secret: string; provisioningUri: string }>('/admin-mfa/enroll').then((r) => r.data),
  confirm: (code: string) => api.post<{ recoveryCodes: string[] }>('/admin-mfa/confirm', { code }).then((r) => r.data),
  disable: (currentPassword: string | null, googleIdToken: string | null, code: string) =>
    api.post<void>('/admin-mfa/disable', { currentPassword, googleIdToken, code }).then((r) => r.data),
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
  reactivate: (id: string, reason?: string) =>
    api.post<UserSummaryDto>(`/admin/users/${id}/reactivate`, reason ? { reason } : undefined).then((r) => r.data),
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
  activationFunnel: () => api.get<ActivationFunnelDto>('/admin/dashboard/activation-funnel').then((r) => r.data),
  activityTrend: () => api.get<ActivityTrendPointDto[]>('/admin/dashboard/activity-trend').then((r) => r.data),
};

// D-28 PR4-A. SUBSCRIPTION_MANAGEMENT_VIEW/_MANAGE-gated (V99) -- its own permission, not folded
// into PLATFORM_STATS_VIEW, same reasoning as PLATFORM_ANALYTICS_VIEW's own separation.
export const adminSubscriptionsApi = {
  list: () => api.get<SubscriptionSummaryDto[]>('/admin/subscriptions').then((r) => r.data),
  changePlan: (userId: string, planCode: string, reason: string) =>
    api.put(`/admin/subscriptions/${userId}/plan`, { planCode, reason }),
};

// D-28 PR4-C. REFERRAL_MANAGEMENT_VIEW/_MANAGE-gated (V101), same split as adminSubscriptionsApi.
export const adminReferralsApi = {
  list: () => api.get<AdminReferralSummaryDto[]>('/admin/referrals').then((r) => r.data),
  creditReward: (referralId: string, amount: number, reason: string) =>
    api.post(`/admin/referrals/${referralId}/credit`, { amount, reason }),
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

// Integrations page -- which third-party services Finora talks to, their live status (reusing
// the same health registry adminDashboardApi's overview.health draws from), and what's planned
// but not yet built. Shares PLATFORM_DIAGNOSTICS_VIEW with adminSystemApi/adminDiagnosticsApi.
export const adminIntegrationsApi = {
  overview: () => api.get<IntegrationsOverviewDto>('/admin/integrations').then((r) => r.data),
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
  // No default window on the backend (an unbounded scan is a cost the endpoint never silently
  // absorbs) -- since is required here too, an ISO string built from a plain Date so callers
  // don't need to know the backend's exact format.
  gmailParserStats: (since: Date) =>
    api.get<GmailMerchantParserStatDto[]>('/admin/merchants/gmail-parser-stats', {
      params: { since: since.toISOString() },
    }).then((r) => r.data),
};

/** Admin CRUD + a test sandbox for Gmail merchant templates -- lets an admin add or fix a
 *  declarative receipt parser without a backend deploy. Gated MERCHANT_MANAGE, same as
 *  adminMerchantsApi above -- not SYSTEM_SETTINGS -- see AdminMerchantTemplateController's own
 *  class doc for why. New templates come back disabled; activate is a separate call, always
 *  taken only after a successful test (see MerchantTemplates.tsx's own TestTemplatePanel). */
export const adminMerchantTemplatesApi = {
  list: () => api.get<MerchantTemplateDto[]>('/admin/merchant-templates').then((r) => r.data),
  create: (request: CreateMerchantTemplateRequest) =>
    api.post<MerchantTemplateDto>('/admin/merchant-templates', request).then((r) => r.data),
  update: (id: string, request: UpdateMerchantTemplateRequest) =>
    api.put<MerchantTemplateDto>(`/admin/merchant-templates/${id}`, request).then((r) => r.data),
  activate: (id: string) =>
    api.post<MerchantTemplateDto>(`/admin/merchant-templates/${id}/activate`).then((r) => r.data),
  deactivate: (id: string) =>
    api.post<MerchantTemplateDto>(`/admin/merchant-templates/${id}/deactivate`).then((r) => r.data),
  // Dry-run against a pasted sample email -- never creates or persists a template. See
  // AdminMerchantTemplateController's /test endpoint doc comment.
  test: (request: TestMerchantTemplateRequest) =>
    api.post<TestMerchantTemplateResult>('/admin/merchant-templates/test', request).then((r) => r.data),
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

/** One transaction, traced from raw to final classification -- Phase 2's Founder Operations
 *  Dashboard, Reconciliation Explorer (AdminReconciliationExplorerController). Same
 *  RECONCILIATION_VIEW permission as adminReconciliationApi above; a 404 means no transaction
 *  with that id exists, not a permission problem. */
export const adminReconciliationExplorerApi = {
  trace: (transactionId: string) =>
    api.get<ReconciliationExplorerTrace>(`/admin/reconciliation/explorer/${encodeURIComponent(transactionId)}`)
      .then((r) => r.data),
};

/** One user's dashboard insights, traced back to the transaction set and formula that produced
 *  each number -- Phase 2's Founder Operations Dashboard, Insight Explorer
 *  (AdminInsightsExplorerController). Gated on INSIGHTS_EXPLORER_VIEW, its own permission -- see
 *  that controller's own comment for why this isn't folded into USER_VIEW or
 *  RECONCILIATION_VIEW. A 404 means no user with that id exists, not a permission problem. */
export const adminInsightsExplorerApi = {
  trace: (userId: string) =>
    api.get<InsightsExplorerTrace>(`/admin/insights/explorer/${encodeURIComponent(userId)}`)
      .then((r) => r.data),
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
  /** Premium Import Reliability v1, §4.2 -- the "user emailed us" entry point. Returns the
   *  customer's own recent failed imports (reference, file name, failure code), each reference
   *  feeding straight into adminImportTraceApi.byAnalysis for the full trace. */
  failuresByUser: (email: string, limit?: number) =>
    api.get<CustomerFailureSummary[]>('/admin/imports/analyses/failures/by-user', { params: { email, limit } }).then((r) => r.data),
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

/** One import, row by row -- Founder Operations Dashboard, Import Row Trace
 *  (AdminImportRowTraceController). Same PLATFORM_DIAGNOSTICS_VIEW permission as
 *  adminImportTraceApi above; a 404 means no statement import with that id exists. */
export const adminImportRowTraceApi = {
  trace: (statementImportId: string) =>
    api.get<ImportRowTrace>(`/admin/imports/row-trace/${encodeURIComponent(statementImportId)}`)
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
