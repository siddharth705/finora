# Admin portal — all 33 pre-existing `tsc -b` errors fixed

These were flagged as out-of-scope during the deployment-readiness pass (confirmed unrelated to
those changes at the time) — now fixed properly since you've shared the actual output.

## Root cause 1: unsound `AdminAuthState` mocks (26 of the 33 errors)

Every test mocking `useAdminAuth()` built a **partial** object (just whichever fields that test
cared about) and force-cast it: `{ ...partial... } as ReturnType<typeof useAdminAuth>`.
TypeScript correctly flags this once the partial shape stops overlapping enough with the real
11-field `AdminAuthState` interface to look like a legitimate narrowing rather than a mistake.

**Fixed properly, not patched around:**
- Exported `AdminAuthState` from `AdminAuthContext.tsx` (was previously unexported, so no test
  file could reference the real type at all).
- New `src/test/mockAdminAuth.ts` — `mockAdminAuthState(overrides)` returns a fully-valid
  `AdminAuthState` with sane defaults for all 11 fields, letting each test override only what it
  actually exercises. The compiler now checks the *whole* object against the real interface, so a
  typo'd field name fails to compile instead of silently mocking nothing — meaningfully safer
  than the escape-hatch `as unknown as X` cast TypeScript's own error message suggests.
- Updated all 13 files that had this pattern: `ProtectedRoute.test.tsx`, `Sidebar.test.tsx`,
  `AuditLog.test.tsx`, `Banks.test.tsx`, `FeatureFlags.test.tsx`, `GlobalRules.test.tsx`,
  `LearningEngine.test.tsx`, `MerchantIntelligence.test.tsx` (3 call sites), `Users.test.tsx`,
  `PlatformAnalytics.test.tsx`, `ReconciliationMonitor.test.tsx`, `SystemHealth.test.tsx`, plus
  `Login.test.tsx` (wasn't a build error — it already used the `as unknown as X` escape hatch, so
  it compiled — but brought it in line with everything else while already here).

## Root cause 2: missing index signatures (5 errors)

`useSavedViews<T>`/`FilterBar<T>` both constrain `T extends Record<string, string>` — a
deliberate, correct design (the hook JSON-serializes filter values into `localStorage`, so a flat
string-keyed shape is a real requirement, not an arbitrary restriction). `AuditFilterValues`,
`UserFilterValues`, and the test-local `Filters` type in `useSavedViews.test.ts` all had every
property correctly typed `string` — but TypeScript doesn't consider a plain interface with named
string properties structurally assignable to an index-signature type without an *actual* index
signature.

**Fixed:** added `[key: string]: string;` to all three.

## Root cause 3: an actually-wrong local test helper (7 errors)

`AuditLog.test.tsx` had its own copy-pasted `pagedResponse()` helper, hardcoded to
`content: unknown[]` instead of being generic — so no matter what real `AuditLogDto[]` was passed
in, the mocked response's `content` came back typed `unknown[]`, failing to satisfy
`PagedResponse<AuditLogDto>`. (`Users.test.tsx` had its own separate copy that happened to be
correctly typed already — left as-is, not broken.)

**Fixed:** new `src/test/pagedResponse.ts` — one shared, genuinely generic
`pagedResponse<T>(content: T[], overrides?)`. `AuditLog.test.tsx` now imports it instead of
keeping its own wrong copy.

## Verification

`tsc -b` on `admin-portal/` — **clean, 0 errors** (down from 33). Also re-ran `tsc -b` on the
separate `frontend/` app to confirm nothing there was affected (it wasn't touched) — also clean.
Couldn't run the full `npm run build` / `vite build` or the actual test suite myself (same native
Vite/Vitest binary constraint as every frontend round this session) — please run
`npm run build` and `npm test` in `admin-portal/` to confirm.
