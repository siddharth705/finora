# Security Review — Findings, 2026-08-19

**Status:** a from-scratch security review producing findings, not a closure report — nothing here
has been fixed yet. Not a claim that these are the only issues in the repository; see §5 for what
this pass didn't cover.

**Branch reviewed:** `main` @ `9de2f7b4` (2026-08-17T18:50:29+05:30). At review time, local `main`
was **17 commits behind `origin/main`** — this review reflects that commit, not whatever has landed
on origin since. Re-check anything security-sensitive that shipped in those 17 commits.

## 0. Why this exists

An external static-analysis tool ("CodeFlow") produced a report claiming 61 security issues in this
repo. Spot-checking every one of its "HIGH" findings against the actual source found that most were
either fabricated outright (a quoted line of code that does not exist anywhere in the cited file) or
false positives from naive keyword matching (`SpringApplication.run()` flagged as "dynamic code
execution"; a React component named `AppShell` flagged as "shell command execution"). That report is
not usable and should not be acted on. See conversation log for the full spot-check; not reproduced
here.

This document is the result of an independent review run in its place, using parallel review agents
each explicitly briefed on the CodeFlow failure mode and required to back every claim with an actual
read of the cited file — no finding below was written from memory, pattern-matching, or inference
about what the code "probably" does.

## 1. Methodology

Three passes, 11 agents total, all read-only (no code was modified during the review):

1. **Tight pass** (5 agents) — HIGH-confidence, directly-exploitable findings only, covering
   auth/session/JWT, the document-import pipeline, API/IDOR/RBAC/PII, frontend+mobile+admin-portal
   client code, and CI/infra/scripts. **Result: zero findings** — the codebase's core controls
   (ownership checks, JWT/session handling, OAuth) held up.
2. **Broadened pass** (5 agents) — same areas, bar lowered to include real MEDIUM/LOW
   defense-in-depth gaps, still requiring a direct file read per claim. **Result: 8 findings.**
3. **New-area pass** (5 agents) — business-logic/financial integrity, CSRF/session-fixation/account
   enumeration, search-query construction/admin-role-escalation, mobile-specific hardening, and
   rate-limiting consistency on sensitive endpoints. **Result: 10 findings** (one agent, search/RBAC
   escalation, returned zero).

Every agent was given the same hard exclusions used by this repo's `/security-review` skill (no DoS
noise, no client-side-only findings, no theoretical race conditions, no test-file-only issues, no
documentation findings) and instructed to explicitly say "no finding" rather than stretch weak
signal into a report line. Total: **18 findings, 0 HIGH, 9 MEDIUM, 9 LOW.**

## 2. At a glance

| ID | Severity | Category | Finding | Location |
|---|---|---|---|---|
| SEC-01 | MEDIUM | Client storage | Access token in `localStorage`, unlike the refresh token which already avoids this | `frontend/src/context/AuthContext.tsx:56`, `admin-portal/src/api/client.ts:112` |
| SEC-02 | MEDIUM | Resource handling | No decompressed-size/page-count ceiling before full PDF parse | `backend/.../imports/pdf/PdfTextExtractor.java:87` |
| SEC-03 | MEDIUM | Auth | No MFA/second factor on admin-portal accounts | `AuthService.login():382-514` |
| SEC-04 | MEDIUM | PII disclosure | Password-reset returns unmasked phone number (known, BH-015, still open) | `AuthService.resolveResetPasswordPhone():1034-1071` |
| SEC-05 | MEDIUM | Input validation | Unbounded `limit` on one admin endpoint (sibling endpoints all clamp it) | `AdminStatementAnalysisController.java:178-184` |
| SEC-06 | MEDIUM | Business logic | No idempotency protection on manual transaction creation | `TransactionService.java:167-245` |
| SEC-07 | MEDIUM | Auth | Timing-based account enumeration in `forgotPassword` | `AuthService.java:970-1023` |
| SEC-08 | MEDIUM | Mobile hardening | No root/jailbreak detection | `mobile/` (absent) |
| SEC-09 | MEDIUM | Mobile hardening | No biometric app-lock feature | `mobile/` (absent) |
| SEC-10 | LOW | Secrets hygiene | `.gitignore` has no generic key/cert-file pattern | `.gitignore` |
| SEC-11 | LOW | Config validation | `ProductionConfigValidator` doesn't check CORS allowed-origins at boot | `application.yml:218` |
| SEC-12 | LOW | Audit completeness | Admin profile-edit audit entries omit what changed | `AdminUserService.updateProfile():77-143` |
| SEC-13 | LOW | Input validation | No app-level upper bound on manual transaction amount | `TransactionService.java:296-300` |
| SEC-14 | LOW | Auth | Account enumeration via `/register`'s distinct 409 message | `AuthController.java:39-44` |
| SEC-15 | LOW | Auth | No password complexity rule beyond length | `AuthDtos.java:32` |
| SEC-16 | LOW | Rate limiting | `/api/v1/auth/refresh` missing from the rate-limiter list | `RateLimitFilter.java` |
| SEC-17 | LOW | Mobile hardening | No screenshot/screen-recording protection on balance/account screens | `mobile/src/screens/` |
| SEC-18 | LOW | Mobile hardening | No TLS certificate pinning | `mobile/src/api/client.ts:23` |

## 3. Findings

### SEC-01 — MEDIUM — Access token stored in `localStorage`
**Files:** `frontend/src/context/AuthContext.tsx:56`, `frontend/src/api/client.ts:90,185,192,237`,
`admin-portal/src/api/client.ts:112` (`TOKEN_KEY = 'finora_admin_token'`, line 64).

Both the customer frontend and the admin portal persist the JWT access token in `localStorage`.
`safeStorage` (`frontend/src/lib/safeStorage.ts:23`, `admin-portal/src/lib/safeStorage.ts:23`) only
adds crash-safety around storage-blocked browsers — it's still backed by `localStorage`, readable by
any script on the page (XSS, malicious extension). Notably, `AuthContext.tsx:57-60` explicitly
documents that the *refresh* token is deliberately kept out of client-readable storage for exactly
this reason, citing prior bug BH-012 — the same reasoning was never applied to the access token.

**Impact:** an XSS vector anywhere on the page gives an attacker direct, silent read access to a
live session token via `localStorage.getItem('finora_token')`.

**Fix:** hold the access token in memory (React context/state) only; rely on the existing HttpOnly
refresh-token cookie to re-mint it on load (the refresh flow already exists at `client.ts:185-192`).

### SEC-02 — MEDIUM — No PDF decompression/page-count ceiling before full parse
**File:** `backend/src/main/java/com/finora/imports/pdf/PdfTextExtractor.java:87`.

`Loader.loadPDF(fileBytes, password)` is called with no `MemoryUsageSetting` and no page-count
pre-check; `PdfTableLocator.java:432-436` records page count only *after* full text extraction. The
10MB multipart cap (`application*.yml:132`) bounds the compressed upload, not what PDFBox
materializes in memory — a spec-valid PDF with an extreme compression ratio or pathological
page/object count is not caught before `stripper.getText(document)` runs.

**Fix:** add a page-count ceiling checked immediately after `Loader.loadPDF` (reject before
`stripper.getText`); consider `MemoryUsageSetting.setupMixed(...)` to spill to disk.

### SEC-03 — MEDIUM — No MFA for admin-portal accounts
**File:** `AuthService.login():382-514`.

Single entry point for both `SCOPE_USER` and `SCOPE_ADMIN` accounts, protected only by password +
per-account lockout / per-IP rate limiting — the same controls as an ordinary user. No
MFA/TOTP/WebAuthn implementation exists anywhere in the backend. Admin accounts can view/manage
other users' financial data (`AuthorizationService`, `RoleService`), so a phished or reused admin
password is a full, single-factor account takeover.

**Fix:** require TOTP or WebAuthn as a second factor specifically for `SCOPE_ADMIN` accounts.

### SEC-04 — MEDIUM — Password-reset discloses unmasked phone number (known, still open)
**File:** `AuthService.resolveResetPasswordPhone():1034-1071`.

Returns the account's phone number unmasked to anyone holding a valid password-reset token, while
`login()`/`register()` return a masked value. Already documented in-code as a deliberate, tracked
tradeoff (BH-015 — masking would break the client-side Firebase-OTP-send flow) and rate-limited
(10/10min). Included here because it remains a live exposure: an email-inbox compromise yields the
account's phone number, a SIM-swap target, before the reset completes.

**Fix:** no new fix proposed here — this is a tracked, deliberate tradeoff; flagging for visibility,
not as a new discovery.

### SEC-05 — MEDIUM — Unbounded pagination parameter on one admin endpoint
**Files:** `AdminStatementAnalysisController.java:178-184` (`GET
/api/v1/admin/imports/analyses/failures/by-user?limit=`) → `StatementAnalysisRecorder.java:151-158`.

`limit` is passed straight into `PageRequest.of(0, limit)` with no cap. Every sibling method on the
same controller/service pair clamps via `PageBounds.safeSize(...)` — this one call site was missed.
An admin with `PLATFORM_DIAGNOSTICS_VIEW` can pass `limit=Integer.MAX_VALUE` to force an unbounded
read of one customer's failure history in a single query. Admin-only, scoped to one user's rows —
defense-in-depth, not exploitable by an outsider.

**Fix:** wrap with `PageBounds.safeSize(limit, DEFAULT_LIMIT)`, matching the pattern already used two
methods away in the same file.

### SEC-06 — MEDIUM — No idempotency protection on manual transaction creation
**Files:** `TransactionController.java:66-68`, `TransactionService.java:167-245`.

`POST /api/v1/transactions` has no idempotency key; `TransactionService.create()` does an
unconditional insert plus `adjustAccountBalance(...)` on every call. A double-click or client retry
creates two rows and moves the real balance twice. `ReconciliationService` can flag the second row
`DUPLICATE`, but per that method's own doc comment, a `DUPLICATE`-flagged row still counts toward
`Account.balance` — only reports/dashboards exclude it. The import-confirm path was specifically
hardened against this exact class of bug (atomic DB claim + unique content-hash index); manual
creation never received the equivalent protection.

**Fix:** add a client-supplied idempotency key (matching the unique-index pattern already used for
import sessions), or make a `DUPLICATE`-flagged row reverse its balance delta until confirmed rather
than only excluding it from reports.

### SEC-07 — MEDIUM — Timing-based account enumeration in `forgotPassword`
**File:** `AuthService.java:970-1023`.

When the email exists, the method does DB writes and — when a real email provider is configured
(confirmed: `ResendEmailProvider.java`) — registers an `AfterCommit` callback that runs
**synchronously in the request thread** (`AfterCommit.java:66-77`, no thread offload) and blocks on
a live outbound HTTP call before the response returns. A non-existent email returns immediately
after one lookup. Response body/status are identical either way, but the latency gap (a real network
round-trip vs. a single `SELECT`) is a strong, easily measurable oracle.

**Fix:** move the email-provider send onto an async/after-commit path, or pad the non-existent-account
branch with an equivalent dummy delay to equalize timing.

### SEC-08 — MEDIUM — No root/jailbreak detection on mobile
**Scope:** `mobile/` — no jailbreak/root-detection library in `package.json` or `app.config.ts`
plugins, none in `src/`.

For an app storing bearer/refresh tokens and rendering account balances, a compromised OS undermines
the Keychain/Keystore threat model the rest of the app relies on. Reasonable, not urgent, expectation
for a finance app.

**Fix:** add a root/jailbreak detection library and degrade functionality (or warn) on a compromised
device.

### SEC-09 — MEDIUM — No biometric app-lock feature
**Scope:** `mobile/` — `expo-local-authentication` is not a dependency; no biometric-gated lock
screen exists anywhere in `src/`. The locally-generated (gitignored) `ios/Finora/Info.plist` contains
`NSFaceIDUsageDescription`, but that's emitted automatically by the `expo-secure-store` config
plugin, not evidence of an implemented lock feature — nothing in `src/` calls `LocalAuthentication`.

**Fix:** add an optional biometric app-lock gate for the mobile app, consistent with what a
financial app's users would expect.

### SEC-10 — LOW — `.gitignore` has no generic private-key/certificate pattern
**File:** `.gitignore`.

The "Secrets" section ignores `.env*`, `.finora/`, and three Firebase-service-account-JSON name
patterns — all added reactively after real past incidents, per the file's own comments. No generic
glob for `*.pem`/`*.key`/`*.p12`/`*.pfx`/`*.jks`/`*.crt`. Nothing is currently leaked (verified via
`git ls-files`) — this is a preventive gap, not an active exposure.

**Fix:** add a generic key/cert-extension block, scoped/negated the same way the Firebase pattern
was if a legitimate exception is ever needed.

### SEC-11 — LOW — CORS allowed-origins not validated at prod boot
**File:** `application.yml:218`.

`app.cors.allowed-origins` defaults to `http://localhost:5173,http://localhost:5174`.
`ProductionConfigValidator` fails prod boot on missing `JWT_SECRET`/`DB_PASSWORD`/encryption keys but
does not check this property. Fails *closed* (a forgotten override breaks the real frontend, doesn't
open CORS) — an operational-safety gap, not a security hole.

**Fix:** add CORS origin validation to the same boot-time validator for consistency.

### SEC-12 — LOW — Admin profile-edit audit entries omit what changed
**File:** `AdminUserService.updateProfile():77-143`, audit call at 141-142.

An admin can change `fullName`, `phoneNumber`, `lowBalanceThreshold`, `timezone`
(`AdminDtos.java:160-170`); the audit record captures who/when but not which field(s) or old/new
values. Sibling actions (`suspend`, `reactivate`) do include meaningful state in their audit payload.

**Fix:** capture before/after values (or at minimum touched field names) in the
`USER_PROFILE_UPDATED_BY_ADMIN` audit metadata.

### SEC-13 — LOW — No app-level upper bound on manual transaction amount
**Files:** `TransactionService.java:296-300` (`requirePositiveAmount`), `TransactionDto.java:49-53`.

Only checks `amount > 0`; no `@DecimalMax` on `CreateRequest`/`UpdateRequest.amount()`. The DB column
is `NUMERIC(14,2)`, so an oversized amount cleanly 409s via `GlobalExceptionHandler` rather than
crashing — the DB constraint is the actual backstop, not application-level validation. Amount is
correctly `BigDecimal` throughout (no float-precision money bug).

**Fix:** add a `@DecimalMax` bound matching realistic statement magnitudes.

### SEC-14 — LOW — Account enumeration via `/register`
**File:** `AuthService.java:250-258` (`createUserRecord`), surfaced via `AuthController.java:39-44`.

Registering with an already-used email/phone returns 409 with a distinct "already exists" message,
versus success for a new one — a direct, message-based enumeration oracle. Common signup UX
trade-off, but real; no CAPTCHA/rate-limit reviewed for this specific endpoint in this pass.

**Fix:** optional — rate-limit `/register` per IP if not already covered, or accept as a standard
trade-off.

### SEC-15 — LOW — No password complexity requirement
**File:** `AuthDtos.java:32` — `@Size(min = 8, max = 72)` is the only constraint on
`RegisterRequest.password`.

Mitigated by BCrypt (strength 12), per-account lockout, and reuse prevention (`PasswordHistoryService`
blocks the current password and the last 5 hashes) — reuse/complexity protection is otherwise solid.

**Fix:** optional — add a strength check (e.g. zxcvbn) for defense-in-depth beyond length + lockout.

### SEC-16 — LOW — `/api/v1/auth/refresh` not in the rate-limiter list
**File:** `RateLimitFilter.java:266-323`.

Every other session-lifecycle endpoint that consumes a bearer credential is rate-limited under the
class's own "bounds a token holder retrying in a loop" reasoning; `/refresh` — arguably the most
frequently hit — is absent. Not a guessing attack (refresh tokens are 256-bit `SecureRandom`,
computationally infeasible to brute-force); the realistic concern is an unbounded retry ceiling on a
stolen/leaked token or the reuse-detection path.

**Fix:** optional, for consistency — add `/api/v1/auth/refresh` to `limitedEndpoints`.

### SEC-17 — LOW — No screenshot/screen-recording protection on balance/account screens
**Scope:** `mobile/src/screens/` — no `expo-screen-capture`, `FLAG_SECURE`, or iOS capture-protection
API anywhere in `src/`. Balances can be screenshotted/screen-recorded and could appear in the iOS app
switcher. Standard for most consumer apps; worth noting for a finance app specifically.

**Fix:** optional — add capture protection on the dashboard/statement-history screens.

### SEC-18 — LOW — No TLS certificate pinning
**File:** `mobile/src/api/client.ts:23` — plain `axios.create({ baseURL: BASE_URL })`, no pinning
adapter/library in `package.json` or `app.config.ts` plugins. Soft finding: most Expo-managed apps
skip pinning, and `NSAllowsArbitraryLoads: false` otherwise enforces TLS via ATS.

**Fix:** optional — add certificate pinning if the threat model warrants it.

## 4. Checked and confirmed solid (not findings)

Explicitly verified via direct file reads and **not** reported above, to record what was actually
checked rather than only what was wrong:

- **Ownership/IDOR:** centralized via `OwnershipGuard`, enforced by a build-time test
  (`OwnershipGuardUsageTest`); `AccountService`/`TransactionService` mutation paths all route through
  it. `DataExportService.buildBundle` only ever receives `currentUser.id()`, never a client parameter.
- **Admin RBAC:** every `Admin*Controller` carries `@PreAuthorize`; `RoleService.assignRole` and
  `addPermissionToRole` explicitly block self-targeting and self-boosting; last-`SUPER_ADMIN`
  removal is blocked.
- **Search query construction:** fully parameterized JPQL (`@Query` with named binds) everywhere
  checked; `LikePatterns.escape()` consistently applied before `LIKE` binding; no
  `createNativeQuery`/string-built SQL found anywhere.
- **JWT/session:** session liveness checked via `SessionValidator` on every request (closes
  revoked-but-valid-JWT gap); portal-scope cross-checked in `JwtAuthFilter`; refresh-token rotation
  has reuse detection with account-wide revocation and correct `noRollbackFor` usage.
- **Google Sign-In:** signature/issuer/audience verified library-side; auto-link into an existing
  account is refused unless that account's own email is already verified (closes OAuth pre-hijack).
- **CSRF:** disabled, but safe as implemented — every cookie-authenticated state-changing endpoint is
  POST-only, and `SameSite=Lax` is not sent on cross-site POST.
- **Session fixation:** a fresh `SecureRandom` token and fresh session UUID are issued on every login;
  nothing persists across the unauthenticated→authenticated transition.
- **Verification flows:** email/phone verification tokens are single-use, expiring, and (for phone)
  cross-account-replay-blocked by matching the Firebase-verified number to the account's stored one.
- **Currency/balance types:** `BigDecimal` throughout, no float-precision money bugs; negative/zero
  amounts blocked server-side on every write path including import normalization.
- **Merchant rules / ReDoS:** `CategoryRule` has no regex operator — no ReDoS vector exists. Rule
  queries are scoped by `userId`; admin GLOBAL-rule endpoints verify scope before acting.
- **Secrets in config:** every credential-shaped property in `application*.yml` is an
  `${ENV_VAR:default}` reference; `.env.example` files across all four apps contain only placeholder
  or genuinely public values (Firebase web config, OAuth client ID, Sentry DSN); repo-wide grep for
  private-key/AWS-key/GitHub-token/Slack-token patterns found nothing.
- **CI/infra:** no untrusted input (PR title/body/branch name) reaches a `run:` shell step in either
  workflow — only GitHub-validated commit SHAs are interpolated; Dockerfile runs as non-root with no
  baked-in secrets; local dev docker-compose stacks are appropriately scoped and documented as such.
- **Client-side (frontend/mobile/admin-portal):** no `dangerouslySetInnerHTML` in app source, no
  hardcoded secrets, mobile refresh/session data lives in Keychain/Keystore via `expo-secure-store`
  (not `AsyncStorage`), CSP has no `unsafe-eval` or wildcard `script-src`, no `eval()`/`new Function()`
  anywhere in app source, no `postMessage` listeners exist to have an origin-check gap, no mobile deep
  link handler exists yet to have an injection surface (`RootNavigator` has no `linking` prop).

## 5. What this review did not cover

- **The 17 commits between local `main` and `origin/main` at review time** — see the header. Worth a
  targeted re-check after pulling.
- **Load/stress-test-only findings** — explicitly out of scope per the review's exclusion rules (DoS,
  rate limiting, resource exhaustion in the general sense).
- **Outdated third-party dependency CVEs** — flagged as a separate, differently-managed concern; not
  audited version-by-version against a CVE database, only spot-checked for anything the reviewing
  agents specifically recognized.
- **Anything requiring a running system** — this was a static, read-only code review; no live
  requests were sent, no exploit was executed end-to-end. Severity/impact assessments are based on
  code inspection, not live proof-of-concept.
- **Areas outside the 11 agents' assigned scope** — e.g., the admin-portal's own build/deploy pipeline
  beyond what CI/infra covered, and any backend module not touched by transactions/auth/imports/admin
  (e.g., notification or Gmail-sync code, which per project tracking is design-only and not yet built).
