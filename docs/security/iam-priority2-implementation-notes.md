# Priority 2 (IAM) — Implementation Notes

Companion to [`engineering-directive-phase1.md`](../project-management/plans/engineering-directive-phase1.md). This tracks
what's actually landed for Priority 2 against what the directive asked for, so the gap is visible
rather than implied.

## What already existed before this pass

A meaningful chunk of the "Authentication" scope was already built during the Priority 1 bug-fix
pass, just not labeled as Priority 2 work at the time:

- JWT access tokens + refresh token rotation (`JwtService`, `RefreshToken` entity, `RefreshTokenService`)
- BCrypt password hashing (strength 12) — `SecurityConfig.passwordEncoder()`
- Login attempt tracking and account lockout — `User.failedLoginAttempts` / `lockedUntil`,
  enforced in `AuthService.login()` (5 attempts, 15-minute lockout, audited as `ACCOUNT_LOCKED`)
- Logout via refresh token revocation — `AuthService.logout()`
- Password reset framework — `PasswordResetToken`, `AuthService.forgotPassword()`
- Phone (not yet email) verification framework — `OtpService`, `PhoneVerificationFilter`
- Audit logging foundation — `AuditService` / `AuditLog`, already wired into auth, transactions, accounts
- Method security enabled (`@EnableMethodSecurity`) with one real RBAC-gated endpoint (`AdminController`)

None of that needed rebuilding. What follows is what this pass actually added.

## What this pass added

**Database-driven RBAC**, replacing the single `users.role` string as the sole authorization
signal:

- `V16__rbac_roles_permissions.sql` — `roles`, `permissions`, `role_permissions`, `user_roles`
  tables; seeds `SUPER_ADMIN` / `ADMIN` / `USER` roles and the permission set from the directive;
  backfills every existing user's legacy `role` string into an explicit `user_roles` row.
- `Role` / `Permission` entities, `RoleRepository` / `PermissionRepository`.
- `AuthorizationService` — computes a user's effective granted authorities as the union of (a)
  their legacy `role` string resolved against a matching `Role`, and (b) any explicit `user_roles`
  assignments. This is deliberately additive-only: a user's access under the new system is always
  a superset of what they had before, so turning it on can't lock anyone out. See the class-level
  doc comment and `AuthorizationServiceTest` for the reasoning and the behavior it locks in.
- `CurrentUserDetailsService` now sources authorities from `AuthorizationService` instead of a
  single hardcoded `"ROLE_" + user.getRole()`.
- `RoleAdminController` — `GET /api/v1/admin/roles`, `GET /api/v1/admin/permissions`,
  `POST`/`DELETE /api/v1/admin/users/{userId}/roles/{roleName}`, gated by the `ROLE_MANAGE` /
  `PERMISSION_MANAGE` permissions rather than `hasRole('ADMIN')`.
- `AdminController`'s existing audit-log endpoint switched from `hasRole('ADMIN')` to
  `hasAuthority('AUDIT_VIEW')` — the permission-based pattern the directive asks for, applied to
  the one endpoint that already existed. Behavior-preserving: `ADMIN`'s seeded permission set
  includes `AUDIT_VIEW`, so every caller who could reach this endpoint before still can.

Note the role hierarchy this seeds: `ADMIN` deliberately does **not** get `ROLE_MANAGE` /
`PERMISSION_MANAGE` / `USER_CREATE` — granting the ability to grant permissions, or to mint new
accounts, is reserved for `SUPER_ADMIN`. `RoleAdminControllerIT` asserts this explicitly (a
legacy `ADMIN` user gets 403 from `/api/v1/admin/roles`), so it can't regress silently.

## What's deliberately deferred, not silently skipped

- **Resource ownership** is not newly formalized as a shared utility in this pass. It's already
  enforced today, consistently, by every account/transaction/budget/goal service scoping its
  queries to `CurrentUser.id()` — that pattern was already correct and didn't need touching. The
  directive's `CanAccessResource = HasPermission AND (IsOwner OR HasElevatedOverride)` formula
  describes what `AdminController` / `RoleAdminController` already do (permission check first,
  then deliberately cross ownership as the "elevated override" case) — it just isn't extracted
  into a named, reusable helper yet. Worth doing once a second or third controller needs the same
  override shape; one usage doesn't justify the abstraction yet.
- **Rolling every other controller onto `hasAuthority(...)` checks** wasn't attempted here. The
  two controllers touched (`AdminController`, `RoleAdminController`) are the only ones that were
  ever role-gated in the first place — everything else is ownership-scoped, not role-scoped, so
  there's nothing to convert yet. As permission-gated actions get added (e.g. an admin
  "recategorize any user's transaction" feature), they should follow this same pattern from day
  one rather than defaulting back to `hasRole(...)`.
- **Session management** beyond refresh-token revocation (e.g. "list my active sessions / revoke
  a specific one") isn't built. `RefreshTokenService` already supports revoking by token; a
  user-facing "sessions" list is a UI + a new query, not an architecture change, and can follow
  later without touching what's here.
- **Email verification** framework doesn't exist yet — only phone verification does. Would follow
  the same shape as `OtpService`/`PhoneVerificationFilter` if/when it's prioritized.
- **Backend lint/format enforcement** (Checkstyle/Spotless) and the frontend's missing ESLint
  config are still open from the Priority 1 pass — unrelated to IAM, tracked in `CHANGELOG.md`.

## A note on verification

This backend has no `mvn`/Maven available in the sandbox this was built in (no network access to
resolve anything, and no pre-populated local repo), so none of this — including the new tests —
has actually been compiled or run here. Everything was written by hand against the existing
codebase's own conventions (entity style, repository style, `AbstractIntegrationTest`'s
Testcontainers-Postgres pattern, `ApiException`/`GlobalExceptionHandler` usage) and cross-checked
against the actual source of every class it depends on, but "compiles and the tests pass" still
needs confirming with `mvn test` in an environment that has both Maven and Docker (for
Testcontainers) available — e.g. your local machine or CI.
