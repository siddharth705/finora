# Finora — Phase 1 Scaffold

This is a working slice of the Finora PRD (v1.0), built on the exact stack specified:
**React + TypeScript + Vite + Tailwind** (frontend) and **Spring Boot 3 / Java 21 +
Spring Security + PostgreSQL + Flyway** (backend).

It is a *scaffold*, not the finished 20-module product described in the PRD — see
"Known Gaps" below for exactly what's stubbed and why, and "Next Steps" for how to
extend it.

**Design note:** the frontend follows a specific reference design (dark sidebar nav,
light indigo-accented dashboard, dark-hero marketing site) — colors, layout, and
typography (Inter, via `tailwind.config.js`) are all driven from that reference
rather than an earlier internal prototype look.

## What's implemented

**Backend** (`/backend`)
- JWT authentication (register/login), BCrypt password hashing, Spring Security wired end-to-end
- Multi-account support: savings, credit cards, wallets, investments
- Transaction CRUD with filtering/sorting/pagination (backs the Ledger page)
- CSV statement import: auto-detects column name variants, stages rows for review, commits on confirm
- Self-learning categorization: keyword rules + a per-user learned-mapping table that improves as you correct categories
- Reconciliation: duplicate detection + internal-transfer matching between your own accounts
- Budgets (per-category monthly limits, spend tracking)
- Goals (target/current/deadline, contribution history)
- Dashboard summary endpoint: KPIs, month-over-month deltas, a 5-factor financial health score, category breakdown, due-date notifications
- Postgres schema via Flyway migration (`V1__init_schema.sql`)

**Frontend** (`/frontend`)
- Login / Register pages calling the real auth endpoints
- Dashboard page: KPI cards, health score breakdown, category doughnut chart (Chart.js)
- Ledger page: filterable transaction table
- Import page: CSV drag-and-drop, staged review table, confirm-to-commit
- Budgets and Goals pages
- Setup page for managing accounts/cards
- Axios client with JWT auto-attach and 401 → redirect-to-login handling

## Architecture hardening (Phase 1.5)

Following an internal architecture review, the "worth doing now" items were implemented directly:

- **API versioning** — every endpoint moved to `/api/v1/...`; the frontend's Vite proxy matches by prefix so this needed no proxy changes, just an updated `baseURL`.
- **Swagger/OpenAPI** — `springdoc-openapi`, live at `/swagger-ui.html` in dev; **disabled by default in the `prod` profile** (a public API spec is reconnaissance material on a financial API — re-enable deliberately, e.g. behind internal-network access, if you actually need it in production).
- **Environment profiles** — `application.yml` is now the shared base; `application-dev.yml` / `application-test.yml` / `application-prod.yml` override logging verbosity, the datasource (test uses an isolated `finora_test` DB), and Swagger exposure. Activate via `SPRING_PROFILES_ACTIVE` (docker-compose defaults to `dev`).
- **Security headers** — CSP, HSTS, X-Frame-Options (deny), Referrer-Policy, and Permissions-Policy added to `SecurityConfig`.
- **Soft deletes** — `transactions`, `accounts`, `budgets`, and `goals` all use Hibernate's `@SQLDelete` + `@SQLRestriction`: calling `repository.delete(...)` now issues an `UPDATE ... SET deleted_at = now()` instead of a real `DELETE`, and every existing query (derived and JPQL) automatically excludes soft-deleted rows. No repository or service code needed to change for this to work correctly.
- **Real audit trail** — `AuditLog` entity/repository/service, wired into `AuthService` (register, login, password reset), `TransactionService` (create, delete, category update, bulk ops), and `AccountService` (create, update, delete). Extending this to `BudgetService`/`GoalService` is the same 2-line pattern repeated — see `AuditService.record(...)` call sites for the template.
- **Standardized response envelope** — every endpoint now returns `ApiResponse<T>` (`success`, `message`, `data`, `timestamp`, `errorCode`), including error responses via `GlobalExceptionHandler`. Deliberately skipped granular per-domain error codes (`AUTH_001`, `TXN_005`, etc.) for now — `errorCode` currently holds the HTTP status name (`NOT_FOUND`, `VALIDATION_ERROR`); a finer taxonomy is easy to grow later and easy to get wrong upfront. The frontend's Axios client transparently unwraps this envelope in a response interceptor, so `endpoints.ts` and every page that calls it needed zero changes.

**Deliberately deferred** (per the same review, see architecture discussion): Redis caching, feature flags, domain events/event-driven architecture, full DDD/Clean Architecture restructuring, notification abstraction, scheduled jobs, and a full observability stack (Prometheus/Grafana/tracing) — all of these solve problems that don't exist yet at this scale and are cheaper to add later than to maintain speculatively now.

## Architecture hardening, round 2

- **Correlation IDs** — `CorrelationIdFilter` runs before Spring Security, so even a 401/403 gets a request ID. Propagated into the logging pattern, every `ApiResponse` (read from MDC), and the audit trail (`audit_logs.request_id`).
- **Refresh tokens** — access tokens dropped to 15 minutes; opaque, hashed refresh tokens (30 days) with rotation (every use invalidates itself and issues a new one) and reuse detection (a revoked token presented again revokes every active session for that user — see `RefreshTokenService`). Frontend's `client.ts` retries once via silent refresh on a 401 before giving up.
- **RBAC** — `@EnableMethodSecurity` + the first real `@PreAuthorize("hasRole('ADMIN')")`-gated endpoint (`AdminController`, viewing another user's audit trail — a genuine support/investigation use case, not a demo stub). Proven by `AdminRbacIT` — the test actually confirms a USER gets 403 and an ADMIN gets 200, not just that the annotation compiles.
- **Rate limiting** — in-memory, per-IP, fixed-window, applied only to `/auth/login`, `/auth/register`, `/auth/forgot-password` (the endpoints callable without a valid credential already). Honestly single-instance-only — see `RateLimiter`'s Javadoc for why Redis is the right upgrade once there's a second instance to synchronize across, and why building that now would be premature.
- **CI** — `.github/workflows/ci.yml` runs the full backend test suite (including the Testcontainers integration tests — GitHub-hosted runners have Docker available by default) and a frontend type-check/build, on every push and PR.

**Before CI will actually pass**: run `npm install` inside `frontend/` locally and commit the resulting `package-lock.json`. It doesn't exist in this scaffold because generating one requires network access this environment doesn't have, and the CI workflow uses `npm ci`, which requires a lock file to already exist.

## Intelligence layer (redefined Phase 1)

Per the team's product-vision discussion: before any external connector (Gmail, Open Banking, etc.), the processing pipeline itself needed to be genuinely good — since every future data source feeds the same pipeline, whatever's weak here stays weak regardless of where data comes from.

- **"Ask Once, Learn Forever"** — when the categorization engine has no confident guess (`CategorizationService.suggest()` returns `source="default"`), the transaction is filed under "Other" but flagged (`needs_category_review`) instead of silently learned as a real decision. `GET /transactions/needs-review` backs a Dashboard card where resolving one calls the same category-update endpoint used everywhere else — which both applies the category and teaches the merchant map, so that merchant is never asked about again.
- **Fixed a real bug while building this**: `CsvImportService.confirm()` was previously calling `learn()` unconditionally for every imported row, including ones where the engine had no idea and the row was left as "Other" by default — meaning the merchant map was being taught "this merchant = Other" from a non-decision, not a correction. Now only genuine decisions (a confident guess, or the user explicitly choosing something) get learned; unresolved low-confidence guesses get flagged for review instead. Locked in by `CsvImportServiceAskOnceTest`.
- **A `GET /categories` endpoint** — didn't exist before; needed for the review card's category picker and now also makes the CSV staging table's category actually editable (previously it displayed the suggestion as static text with no way to correct it before import).
- **Recurring transaction / subscription detection** — `RecurringService` ports the browser prototype's interval-detection algorithm (regular gap + consistent amount across occurrences of the same merchant) into the production backend, persists the result onto `Transaction.recurring`, and surfaces it on the Insights page with next-expected-date estimates. Covered by `RecurringServiceTest` (monthly detection, one-off rejection, irregular-interval rejection, inconsistent-amount rejection, stale-flag reset).
- **Merchant intelligence**: deliberately *not* a separate `Merchant`/`MerchantAlias` schema — the existing `MerchantCategoryMap` (normalized-description → category, keyed at merchant granularity already) does this job without a bigger data-modeling project. Revisit if there's ever real evidence description-level matching is limiting something.

**Deliberately not built this round, per the reconciled roadmap discussion**: Gmail integration (starts an external CASA compliance process outside your control, and doesn't remove the PDF-parsing problem — just adds inbox scraping on top of it), PDF statement parsing, natural-language financial Q&A (needs to be grounded in real query results, not generated from context, before it's trustworthy), and anything advice-adjacent (affordability/cashback optimization — worth a regulatory check first). A generic "Data Sources" connector abstraction is intentionally deferred until there's a second real connector to abstract from — designing an interface around one implementation usually gets it wrong.

## Real email delivery, TanStack Query, and deployment prep

- **Real email delivery** — `EmailService` abstraction with two implementations: `ResendEmailService` (real HTTP call to Resend's API via Spring's `RestClient`, no new dependency needed) and `NoOpEmailService` (logs instead of sending). `EmailConfig` picks whichever one applies based on whether `RESEND_API_KEY` is set. With no key configured, `forgotPassword()` behaves exactly as before (returns the link in the response); with a key configured, it sends a real email and stops returning the link in the response (no reason to also leak it once real delivery exists). Covered by `AuthServiceEmailTest`.
- **TanStack Query** — `QueryClientProvider` wraps the app in `App.tsx`. `Ledger.tsx` and `Dashboard.tsx` are fully migrated as the demonstrated pattern (`Ledger` shows off `keepPreviousData` + a debounced-value pattern for the search box; `Dashboard` uses `useQueries` so one failing query — e.g. insights failing — doesn't blank the whole page, generalizing what used to be a single hardcoded `.catch()`). **Not yet migrated**: Budgets, Goals, Investments, Reports, Insights, Settings, Setup, Import, and `AskOnceCard` still use the manual `useEffect`/`useState` pattern — same mechanical refactor, not done everywhere yet to keep this round's scope bounded.
- **A real bug caught while wiring the debounced search box**: the first draft of `Ledger.tsx`'s debounce hook used `useState`'s lazy initializer (`useState(() => {...})`) instead of `useEffect` to schedule the debounce timer — that function only runs once on mount, so it would never have actually debounced anything after the first render. Fixed before shipping.
- **A real bug caught while wiring email config**: the first draft of `application.yml` had two separate top-level `app:` keys (one for JWT config, one for email config) — YAML doesn't merge duplicate top-level keys, so the second block would have silently overwritten the first, wiping out `app.jwt.secret` and every other JWT setting. Caught by explicitly checking for duplicate `app:` keys before moving on, not by luck.
- **Deployment prep, not an actual deploy** — I don't have cloud credentials or network access in this environment, so I can't execute a real deployment myself. What's here: `frontend/vercel.json` (SPA routing config for Vercel) and this list of what a real deploy needs:
  - Backend (Railway or Fly.io): set `SPRING_PROFILES_ACTIVE=prod`, a real random `JWT_SECRET` (32+ chars — never reuse the placeholder default), `DB_*` pointing at a real Postgres instance, `CORS_ORIGINS` set to your actual frontend domain, and optionally `RESEND_API_KEY`/`EMAIL_FROM`/`APP_BASE_URL` for real password-reset emails.
  - Frontend (Vercel): point it at the repo's `frontend/` directory; `vercel.json` handles the build command and SPA routing. Update `vite.config.ts`'s dev-only proxy target or add a production API base URL if the backend isn't on the same domain.

## Password visibility, mobile number + OTP verification, and two real production bugs fixed

- **Password visibility toggle** — `PasswordInput` component (eye/eye-off icon) used on Login, Register, and both Reset Password fields.
- **Mobile number at registration** — `RegisterRequest` now requires `phoneNumber` (validated: 10-15 digits, optional `+` country code).
- **OTP phone verification is Firebase Phone Authentication** — the frontend (both `frontend/` and `admin-portal/`) sends and confirms the OTP directly against Firebase via `lib/firebase.ts`/`lib/phoneAuth.ts` (invisible reCAPTCHA + `signInWithPhoneNumber`); the backend never generates, stores, or sends an OTP itself. It only ever sees the resulting Firebase ID token, verified server-side via the Firebase Admin SDK behind the `PhoneVerificationProvider` interface (`FirebasePhoneVerificationProvider` is the only implementation today). Every OTP-gated flow (registration, password reset, authenticated password change) calls `PhoneVerificationProvider.verifyAndGetPhoneNumber()` and then checks the attested phone number against the account's own stored number — a cryptographically valid token for the *wrong* phone number is rejected as firmly as an invalid one. `VerifyPhone.tsx` handles entry + resend in both apps; login/register both report `phoneVerified` so the frontend routes to `/verify-phone` only when needed. (An earlier backend-owned OTP stack — `OtpService`/`SmsService`/Twilio/MSG91 — was fully removed in favor of this; see `docs/engineering/deployment-guide.md` for the `GOOGLE_APPLICATION_CREDENTIALS`/`VITE_FIREBASE_*` config this needs.)
- **A real bug caught while building the original OTP endpoints**: they were initially going to live under `/api/v1/auth/**`, which is entirely `permitAll` in `SecurityConfig`. Since verifying a phone needs a real authenticated user (`CurrentUser.id()`), putting it there would have let unauthenticated requests reach code that assumes a valid principal exists. Moved to a dedicated `PhoneController` at `/api/v1/phone/**` instead, which correctly falls under the default `authenticated()` rule — still true of the current single `/api/v1/phone/verify` endpoint.
- **Two real production bugs fixed this round, found via actual `docker compose` runs, not static analysis**:
  1. **CORS was misconfigured architecturally** — a standalone `CorsFilter` `@Bean` has no defined ordering relative to Spring Security's own filter chain (Spring's own docs warn against this pattern). It caused `permitAll` endpoints to still reject real browser requests (which always send an `Origin` header) while `curl`/`TestRestTemplate` succeeded (they don't send one by default) — masking the bug in every earlier test. Fixed by wiring CORS through `HttpSecurity.cors(cors -> cors.configurationSource(...))` instead, the way Spring Security actually expects it. A new test (`login_withOriginHeader_succeedsAndReturnsCorsAllowOriginHeader`) explicitly sends an `Origin` header — without that, the test suite would have passed against the broken code too.
  2. **`JwtAuthFilter` could crash on a stale token** — if a token was structurally valid (not expired) but referenced a user that no longer existed (e.g. after a database reset while an old token was still in `localStorage`), `loadUserByUsername()` threw uncaught, crashing the whole filter chain with an opaque, empty-body 403 — even on `permitAll` endpoints like `/auth/register`. Fixed by wrapping the authentication logic in try/catch and failing open (treat as unauthenticated, let Spring Security's own rules decide) on any error.
  3. Also applied the frontend hygiene fix that surfaced during the same investigation: the Axios request interceptor no longer attaches a Bearer token to `/auth/login`, `/auth/register`, `/auth/refresh`, `/auth/forgot-password`, or `/auth/reset-password` at all — those endpoints never need it, and not sending a stale one is simply cleaner.

## Known Gaps (intentionally not built, and why)

| PRD item | Status | Why |
|---|---|---|
| Google OAuth, 2FA | Not implemented | Needs real OAuth client credentials and a TOTP library — meaningless to stub without your own Google Cloud project |
| Real AI / OpenAI integration | Not implemented | Insights are real (statistical, computed from your actual transactions), but not LLM-generated. `CategorizationService.suggest()` and `InsightsService.build()` are the seams where you'd swap in a real model call |
| Password reset emails | Token flow is real (hashed, expiring, single-use), but there's no email service wired up — `forgotPassword` returns the raw reset link directly in the API response instead of emailing it. Remove `devResetLink` once real email delivery exists |
| Redis | Not used | Nothing in Phase 1 needs a cache yet — add it when you have a real performance bottleneck to justify it |
| True multi-tenant isolation (RLS, separate schemas) | Not implemented | Every query is scoped by `user_id` at the application layer, which is correct but not database-enforced tenant isolation — that's a Phase 5 concern |
| AWS/Vercel deployment | Not implemented | Requires your own cloud accounts; `docker-compose.yml` gets you a working local environment first |
| Razorpay billing / subscriptions | UI-only, disabled | No payment gateway wired up — the "Upgrade to Premium" button is intentionally disabled rather than pretending to work |
| Excel export | Not implemented | CSV export and Print/PDF work; Excel export needs a library (SheetJS) not yet added to the frontend |
| Excel export from the admin portal | Not implemented | Same gap as the user app's CSV/PDF-only export — no library wired up yet |

## What's real vs. what's a preview, by page

| Page | Status |
|---|---|
| Dashboard, Ledger, Import, Budgets, Goals, Setup/Accounts | Fully wired to the real backend |
| Investments | Real — uses actual `INVESTMENT`-type accounts and a real net-worth-snapshot history |
| Reports | Real — month-parametrized category/income/expense data from real transactions |
| Insights | Real statistics (category deltas, top merchant) — not an LLM, see Known Gaps |
| Settings | Profile is real (read-only); low-balance threshold and theme persist to your account; Security and Subscription sections are honestly disabled, not faked |
| Forgot/Reset Password | Real token issuance and password update; email delivery is stubbed (see Known Gaps) |

## Running it locally

```bash
# 1. Start Postgres + backend
docker compose up -d

# 2. User frontend
cd frontend
npm install
npm run dev

# 3. Admin portal (optional, separate app — see "Admin portal" below)
cd admin-portal
npm install
npm run dev
```

User frontend runs at `http://localhost:5173`, admin portal at `http://localhost:5174`,
backend at `http://localhost:8080`. API docs (dev profile only): `http://localhost:8080/swagger-ui.html`.
Both frontends' Vite dev servers proxy `/api/*` to the backend (see each app's own
`vite.config.ts`), so there's no CORS friction while developing either one.

**This alone won't get you past registration.** `docker-compose.yml` sets no
`GOOGLE_APPLICATION_CREDENTIALS`, and phone verification is enforced server-side
(`PhoneVerificationFilter`) before any account reaches the dashboard — every verification call
503s until the Firebase Admin SDK is configured. See `docs/engineering/deployment-guide.md`'s
environment variable audit for `GOOGLE_APPLICATION_CREDENTIALS` /
`GOOGLE_APPLICATION_CREDENTIALS_BASE64` and the frontend `VITE_FIREBASE_*` vars before expecting a
fresh clone to reach the app past sign-up.

**Before running anywhere but your own machine:** change `JWT_SECRET` in
`docker-compose.yml` to a real random 32+ character value, and never commit
real secrets to the repo — use a `.env` file (gitignored) instead.

## Admin portal (`/admin-portal`)

A second, separate React app — its own `package.json`, build, and login screen — rather than an
`/admin` section bolted onto the user app. It talks to the exact same backend (one Spring Boot
app, one user table, one `/auth/login`); what makes a session "an admin session" is entirely
client-side: right after login it calls `GET /api/v1/users/me/access` and only opens the admin
shell if the account holds at least one admin-relevant permission (`USER_VIEW`, `AUDIT_VIEW`,
`ROLE_MANAGE`, `PERMISSION_MANAGE`, `SYSTEM_SETTINGS`, or `PLATFORM_STATS_VIEW` — see
`V16__rbac_roles_permissions.sql` / `V24__admin_platform_stats_permission.sql`). Each nav section
and page individually checks for the specific permission it needs, so a narrowly-scoped role
(say, audit-only) sees just the sections it can actually use, not a 403 wall.

What's in it today:

- **Users** — search/paginate every account, view a detail page (accounts + transactions count,
  role list, recent activity), suspend/reactivate.
- **Roles & Permissions** — view what each seeded role grants; assign/revoke a role on a specific
  user from their detail page. Creating new roles/permissions isn't wired up yet — same "real
  now, more later" approach as the rest of this project (see Known Gaps below).
- **Audit Log** — the platform-wide activity feed, paginated, plus a per-user drill-down from
  each user's detail page.
- **System Health** — built on a `HealthProvider` extensibility interface
  (`backend/src/main/java/com/finora/health/`): `AdminHealthRegistryService` auto-collects every
  Spring bean implementing it (`List<HealthProvider>`, zero manual registration) into one
  worst-status-wins rollup, gated behind `SYSTEM_SETTINGS` rather than the public
  `/actuator/health` endpoint (which deliberately returns no detail — see `application.yml`). Five
  providers exist today, grouped by category: **Platform** — Database (wraps Actuator's own DB/disk-space/ping
  indicators); **Notifications** — Email Provider, SMS Provider; **Financial Intelligence** — Financial
  Intelligence Engine (reconciliation) and Statement Import Pipeline. Adding observability for a new
  module (Redis, a job queue, etc.) is one new `@Component` away, with zero changes to the
  dashboard or alerts panel.
- **Dashboard** — signup/transaction/account volume stat tiles + a live system-status summary.

An account needs `phoneVerified = true` to reach *any* protected endpoint, admin ones included
(`PhoneVerificationFilter` enforces this server-side for every request). The admin portal has no
OTP UI of its own — if an admin account isn't verified yet, sign into the regular user app once
to complete that, then come back.

## Mobile app (`/mobile`) — in progress

A third app: React Native + Expo, targeting iOS and Android, talking to the same backend as both
web apps. **User-facing only** — there's no mobile admin portal, matching the same User/Admin
split the web apps already have.

Currently at **Phase 1 (auth)**: sign in, register, forgot password, and phone verification all
work end-to-end against the real backend, on top of the Phase 0 foundation (API client, endpoint
layer, shared types, TanStack Query). The signed-in-and-verified branch is still a placeholder —
Dashboard and the rest of the app arrive in Phase 2.

Route protection is expressed the way React Navigation intends, rather than as a port of the
web's `ProtectedRoute`: `src/navigation/RootNavigator.tsx` derives *which stack exists at all*
from auth state, so a signed-out user has no route to the app to navigate to. Login, register,
and verify never call `navigate()` — they update auth state, and the navigator follows. As on
web, this is UX only; `PhoneVerificationFilter` is the real enforcement.

Change Password is **not** in this phase despite living in the auth family: it's reached from
Settings, which doesn't exist until Phase 5, so it would be unreachable code. It lands with that
screen.

Two things differ structurally from `frontend/`, both forced by the platform:

- **Firebase phone auth is native, not the Web SDK.** `@react-native-firebase/auth` does app
  verification through silent APNs push (iOS) / Play Integrity (Android), so there's no invisible
  reCAPTCHA and no DOM container — compare `mobile/src/lib/phoneAuth.ts` against
  `frontend/src/lib/phoneAuth.ts`. It also means this app **can't run in Expo Go** (native module);
  it needs a dev client / EAS build.
- **Config comes from native files, not env vars.** There are no `EXPO_PUBLIC_FIREBASE_*`
  variables — `GoogleService-Info.plist` and `google-services.json` (gitignored, downloaded per
  developer from the same Firebase project the backend uses) carry it instead. Only
  `EXPO_PUBLIC_API_BASE_URL` is env-driven; see `mobile/.env.example`.

One non-obvious platform trap worth knowing before adding more forms: **don't put `maxLength` on
a field whose `onChangeText` sanitizes input.** React Native applies `maxLength` to pasted text,
so it truncates *before* the handler runs — pasting `+919876543210` into a `maxLength={10}` phone
field yields the wrong number, and pasting a whole OTP SMS yields an empty one. The web app
sidesteps this with a separate `onPaste` handler reading the clipboard directly, which RN has no
equivalent for. Let the sanitizer do the capping instead; both fields in `RegisterScreen` and
`VerifyPhoneScreen` are commented accordingly.

```bash
cd mobile
npm install
cp .env.example .env.local   # then set EXPO_PUBLIC_API_BASE_URL
npm run typecheck
npm start
```

## Next steps, in the order I'd tackle them

1. Wire up the remaining Dashboard UI elements (net worth chart, calendar heatmap) against the data that's already in the schema
2. Add integration tests for `ReconciliationService` and `CategorizationService` — these have the most "business logic" and the most to lose from a silent regression
3. Swap `CategorizationService.suggest()` to call OpenAI when a rule match isn't found, falling back to the rule engine on API failure
4. Add Google OAuth once you've registered a project in Google Cloud Console
5. Set up CI (GitHub Actions: `mvn test` + `npm run build`) before adding more surface area
6. **Password Policy Standardization** — `AuthDtos.PASSWORD_SIZE_MESSAGE` (used for register/reset/change-password) only enforces 8-72 characters; `ChangePasswordModal.tsx`'s strength checklist (uppercase/lowercase/number/special character) is currently framed as a suggestion specifically *because* the backend doesn't enforce it. **Frontend guidance and backend validation must eventually enforce the same policy** — don't let them drift permanently. Before a production release, decide on one real policy and make both sides agree — either the backend starts enforcing the fuller rule (update `PASSWORD_SIZE_MESSAGE`'s `@Pattern`/validation and this same message everywhere it's used), or the frontend checklist gets relabeled as permanently optional guidance. Do not implement complexity enforcement in the frontend alone first and leave the backend behind — update both together.
7. **Prevent password reuse** — `PasswordChangeService.complete()` only rejects `newPassword == currentPassword` (a single comparison, no history). Storing the last N password hashes (e.g. 5) and checking new attempts against all of them is a natural follow-up once there's a real need for it.
8. **Security section growth** — today it's just Password + Phone Verification (see `Settings.tsx`). Active Sessions (list + revoke) is already built end-to-end on the backend — `DeviceController`'s `GET`/`DELETE /api/v1/users/me/devices` on top of `RefreshTokenService` — just no frontend UI for it yet in Settings. The remaining natural additions, each gated on its own real backend capability existing first (no UI ahead of the capability, same discipline as everywhere else in this doc): Trusted Devices, and a Recent Security Activity feed reading from `AuditLog` (`PASSWORD_CHANGED`/`PASSWORD_RESET`/`OTHER_SESSIONS_REVOKED` already carry a `method`/purpose-tagged metadata and `createdAt` — enough to back a "Password changed · 2 Aug 2026 · Authenticated Settings" row whenever that screen gets built; nothing further needs to change on the backend for that specific screen). `PasswordChangeSession`/`PhoneOtp.Purpose` also don't yet track IP address or device — no request-context-capture infrastructure exists anywhere in this codebase today (confirmed by grep), so those fields were deliberately left out rather than added unpopulated; add them together with a real `HttpServletRequest`-reading filter the day a real need justifies one.
