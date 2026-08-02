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
} from '../types';

// Auth reuses the exact same /auth/* endpoints the user app calls -- there is only one backend,
// one user table, one login flow. What makes this "an admin session" is entirely client-side
// (AdminAuthContext gating entry on meAccessApi.get() returning at least one admin permission),
// not a separate auth mechanism.
export const authApi = {
  login: (identifier: string, password: string) =>
    api.post<{ token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }>(
      '/auth/login', { identifier, password }).then((r) => r.data),
  refresh: (refreshToken: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh', { refreshToken }).then((r) => r.data.data),
  logout: (refreshToken: string) =>
    api.post('/auth/logout', { refreshToken }),
  // Same /auth/forgot-password and /auth/reset-password endpoints the user app (frontend/)
  // already calls -- there's no separate admin-specific password reset mechanism, just one
  // shared implementation, same reasoning as login() above and the phone verification endpoints.
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email }).then((r) => r.data),
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
      '/auth/login', { identifier, password }
    ).then((r) => r.data.data),
  complete: (bootstrapToken: string, request: { email: string; password: string; fullName: string; phoneNumber: string }) =>
    rawApi.post<ApiEnvelope<null>>('/setup/complete', request, {
      headers: { Authorization: `Bearer ${bootstrapToken}` },
    }).then((r) => r.data),
};
