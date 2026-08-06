# Finora — End-to-End Test Report

**Date:** 2026-08-07
**Branch:** `feat/merchant-learning-queue` (at `00a59e6`)
**Stack under test:** Postgres 16 (Docker) · Spring Boot backend on `:8080` (dev profile, real
Flyway-migrated schema) · user frontend on `:5173` · admin portal on `:5174` · Playwright/Chromium

No application code was modified. Test-data seeding and new test files under `e2e/` are the only
changes.

---

## Read this first: coverage is partial

The request asked for exhaustive coverage of every module, journey, browser and viewport. **That
was not achieved, and it would be misleading to present this as a complete pass.** What follows is
an honest account of what was exercised, what was found, and what remains untested.

The single biggest reason is documented as Issue 01: **a stock local install cannot produce a
working admin account at all.** Phone verification is enforced server-side with no development
bypass, and it requires Firebase credentials this environment does not have. Every authenticated
journey below was reachable only after seeding `phone_verified = true` directly in Postgres. That
seeding is legitimate test-fixture work, but it means the phone-verification journey itself is
untested, and it blocked the admin-portal and cross-application journeys until late in the session.

### What was exercised

| Area | Depth |
|---|---|
| Bootstrap/setup flow | Full — real installation key → bootstrap login → `/setup/complete` → lockout verified |
| Authentication (API) | Full — login, register, invalid credentials, lockout, rate limiting, JWT forgery |
| Authentication (UI) | Full — login, session persistence across reload, back button, logout, route re-protection |
| Authorization / IDOR | Good — cross-user read/write/delete on accounts and transactions, privilege escalation to admin endpoints |
| File upload validation | Good — valid, empty, corrupt, wrong-type, oversized |
| Import pipeline performance | Good — characterised across four row counts |
| Public pages | Good — landing, register, unknown route |

### What was NOT exercised

Admin portal UI journeys · merchant learning queue (queue, worker, retry, failed events, manual
retry) · cross-application workflows · budgets, goals, reports, investments, settings and profile
UI · PDF import · duplicate detection · merchant detection · password reset and forgot-password
flows · multi-device logout · Firefox and WebKit · tablet and mobile viewports · concurrent users ·
memory-leak profiling.

**Do not read "no issues found" into any of the above — they were not reached.**

---

# Issue 01

## Title
Completing setup permanently bricks the platform when Firebase phone auth is unconfigured

## Severity
**High** (Critical for self-hosted or air-gapped deployments)

## Module
Backend — `BootstrapService`, `SetupService`, `PhoneVerificationFilter`, `FirebaseConfig`

## Steps to Reproduce
1. `docker compose up -d postgres`, start the backend on the `dev` profile with no
   `GOOGLE_APPLICATION_CREDENTIALS` set — i.e. the documented local development path.
2. Read the one-time installation key from `backend/.finora/installation.key`.
3. `POST /api/v1/auth/login` as `BOOTSTRAP_ADMIN` with that key. Succeeds.
4. `POST /api/v1/setup/complete` with a real admin's details. Returns
   `"Setup complete. The bootstrap account has been locked."`
5. Log in as the newly created admin. Succeeds, returns `phoneVerified: false`.
6. Call any admin endpoint with that token.

## Expected Result
The operator who just completed setup can administer the platform.

## Actual Result
Every admin endpoint returns `403 PHONE_VERIFICATION_REQUIRED`:

```
/api/v1/admin/users      -> PHONE_VERIFICATION_REQUIRED
/api/v1/admin/banks      -> PHONE_VERIFICATION_REQUIRED
/api/v1/admin/audit-logs -> PHONE_VERIFICATION_REQUIRED
```

The only route out is `POST /api/v1/phone/verify`, which returns:

```json
{"success":false,"message":"Phone verification is not configured on this server.",
 "errorCode":"SERVICE_UNAVAILABLE"}
```

And there is no way back — the database confirms the bootstrap account is already suspended:

```
BOOTSTRAP_ADMIN        | USER        | ADMIN | phone_verified=t | SUSPENDED
e2e.admin@finora.test  | SUPER_ADMIN | ADMIN | phone_verified=f | ACTIVE
```

The only account that can reach admin endpoints cannot verify; the only pre-verified account is
suspended. Recovery requires direct database access.

## Root Cause
`BootstrapService` deliberately sets `phoneVerified(true)` on the bootstrap account
(`BootstrapService.java:118`) precisely so it can bypass `PhoneVerificationFilter` and reach
`/setup/complete`. The real admin created by `SetupService.completeSetup` does **not** inherit that
treatment, while the bootstrap account is suspended in the same transaction. `FirebaseConfig`
returns `null` when credentials are absent — a deliberate, well-documented design — so
`phoneVerificationProvider` has nothing to delegate to.

Each piece is individually correct. The failure is in their composition: setup is a one-way door
that revokes the only working credential before confirming the replacement can actually be used.

## Recommendation
Make setup completion verify its own exit. Options, roughly in order of preference:

1. Treat the operator who completes setup as verified, exactly as the bootstrap account is — they
   have already proven physical possession of the installation key, which is a stronger proof than
   an SMS.
2. Keep the bootstrap account active until the first real admin successfully verifies, then
   suspend it.
3. Refuse to complete setup at all when no phone-verification provider is configured, failing
   loudly at the point of no return rather than immediately after it.

Option 3 alone still blocks local development; pair it with 1 or a documented escape hatch.

---

# Issue 02

## Title
Realistic statement sizes take minutes to import; permitted 10MB uploads never complete

## Severity
**High**

## Module
Backend — `ImportController.stage` (`POST /api/v1/import/csv/stage`), CSV parsing pipeline

## Steps to Reproduce
Upload generated CSVs of increasing row count to `/api/v1/import/csv/stage` with a valid user
token, and time the response.

## Expected Result
A statement import completes in seconds. The configured 10MB upload ceiling
(`UPLOAD_MAX_FILE_SIZE:10MB`) should describe files the system can actually process.

## Actual Result

| Rows | File size | Result |
|---:|---:|---|
| 100 | 3.9 KB | `200` in **2s** |
| 1,000 | 38 KB | `200` in **15s** |
| 5,000 | 194 KB | `200` in **79s** |
| 20,000 | 774 KB | **no response within 120s** |
| ~400,000 | 10 MB | **no response within 300s** |

Roughly 16ms per row, and the request thread is held for the entire duration.

## Root Cause
Not isolated — profiling was out of scope for this pass. The per-row cost is high enough
(~16ms) that a per-row database round trip or per-row entity load is the likeliest shape. Two
prior findings in `docs/engineering/repository-audit-findings.md` describe adjacent per-row and
N+1 patterns in this area.

## Recommendation
1. Profile a 5,000-row import to find the per-row cost before changing anything — the repository's
   own standing rule is to measure before and after.
2. Lower `UPLOAD_MAX_FILE_SIZE` to a size that can actually be processed, or reject oversized
   **row counts** explicitly with a clear error, so the limit stops advertising a capability that
   does not exist.
3. Consider moving staging off the request thread. Note the connection pool is capped at 10
   (`DB_POOL_MAX_SIZE:10`); a handful of concurrent large imports could plausibly exhaust it.

## Related
Worth checking whether `ImportConcurrencyLimiter` bounds this, and what a user sees in the UI
during a 79-second wait — neither was tested.

---

# Issue 03

## Title
Every unsupported HTTP method returns 500 Internal Server Error instead of 405

## Severity
**Medium**

## Module
Backend — `GlobalExceptionHandler`

## Steps to Reproduce
```
DELETE /api/v1/auth/login          (no auth required)
PATCH  /api/v1/accounts
POST   /api/v1/users/me
GET    /api/v1/accounts/{id}
```

## Expected Result
`405 Method Not Allowed`.

## Actual Result
All return `500`:

```json
{"success":false,"message":"Unexpected error: Request method 'GET' is not supported",
 "errorCode":"INTERNAL_ERROR"}
```

Server-side these log via the catch-all handler as `log.error("Unhandled exception", ...)`.

## Root Cause
`HttpRequestMethodNotSupportedException` has no `@ExceptionHandler` in `GlobalExceptionHandler`
and falls through to the generic 500 branch. This is the **same defect class** already fixed twice
in that class — for `AccessDeniedException`, `OptimisticLockingFailureException`, and most
recently `HttpMessageNotReadableException` in commit `e7dd588` during the preceding audit. The
handler that fix established is the exact template needed here.

## Recommendation
Add an `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` returning a clean 405,
mirroring `e7dd588`. Note this is reachable **unauthenticated** — any bot probing the API generates
`ERROR`-level log entries, which both masks real failures and offers a cheap way to flood
alerting.

---

# Issue 04

## Title
`installationKeyAvailable` still reports `true` after setup completes

## Severity
**Low**

## Module
Backend — `SetupService`, `SetupController.status`

## Steps to Reproduce
1. Complete setup.
2. `GET /api/v1/setup/status` (unauthenticated).

## Expected Result
`{"setupRequired": false, "installationKeyAvailable": false}` — the key is retired.

## Actual Result
```json
{"setupRequired": false, "installationKeyAvailable": true}
```

Backend log during setup:
```
WARN  Could not delete .finora/installation.key after setup completed (permission denied)
      -- safe to remove manually; it will never be read again.
```

## Root Cause
Deletion of `installation.key` failed (a Windows file-locking/permission condition), and
`installationKeyAvailable` is derived from the file's existence rather than from whether the key
is still *usable*. The service degrades safely — the key genuinely cannot be reused, verified
below — but the reported state is wrong.

## Recommendation
Derive `installationKeyAvailable` from setup state rather than file presence, so a failed delete
cannot misreport. The warning itself is good and should stay.

## Note
Not exploitable. Reusing the retired key was tested and correctly refused with `AUTH_003`, and the
bootstrap account is suspended. This is a correctness and operator-confusion issue, not a
vulnerability.

---

## Verified working — no issues found

These were actively probed and behaved correctly. Recording them so the report is a statement of
evidence, not only of defects.

**Authorization and data isolation.** With two separate users, an attacker token was used against
the victim's account and transaction:

| Probe | Result |
|---|---|
| `PUT /accounts/{victimAccount}` | `403` |
| `DELETE /accounts/{victimAccount}` | `403` |
| `DELETE /transactions/{victimTxn}` | `403` |
| `GET /admin/users` as a normal user | `403` |
| Attacker's own `/accounts` list | 0 records — no leakage |
| Attacker's own `/transactions` list | 0 records — victim description absent |

**JWT handling.** Garbage tokens, valid-payload-with-forged-signature, and an `alg:none` forgery
were each rejected with `401`. The `alg:none` result is the notable one — that is the classic JWT
bypass and it is correctly refused.

**Account lockout and rate limiting.** Twelve rapid failed logins produced
`401 ×5 → 423 Locked ×4 → 429 Too Many Requests ×3`. The correct password was then also refused
while locked. Database state confirmed `failed_login_attempts=5` with a `locked_until` timestamp.
Both layers work, and they compose correctly.

**File upload validation.**

| File | Result |
|---|---|
| Valid CSV | `200` |
| Empty file | `400` |
| Binary garbage named `.csv` | `422` |
| `evil.php` containing PHP | `422` |

**Bootstrap security.** After setup, re-login as `BOOTSTRAP_ADMIN` with the original key returned
"This account has been suspended", and replaying `/setup/complete` with the retired bootstrap token
returned `AUTH_003`. The one-time key is genuinely one-time.

**User frontend UI journeys** (Chromium). Login succeeds and leaves `/login`; the session survives
a full page reload (so token persistence and refresh are wired correctly); the browser back button
does not strand the user on a blank screen; logout clears the session and `/app` correctly
redirects back to `/login`. **No console errors were emitted during any authenticated journey** —
asserted explicitly, not eyeballed.

**Input validation.** `fullName` correctly rejects digits; `phoneNumber` enforces its 10–15 digit
pattern; malformed JSON returns a clean `400 MALFORMED_REQUEST_BODY` (the fix from the preceding
audit, confirmed working end-to-end).

---

## Test artifacts

New Playwright specs, committed:

```
e2e/tests/user-portal/smoke.spec.ts      public pages, no backend needed
e2e/tests/user-portal/journey.spec.ts    authenticated journeys, skips if :8080 is down
e2e/tests/admin-portal/smoke.spec.ts     unauthenticated admin surface
```

`journey.spec.ts` skips automatically when the backend is unreachable, so `npm test` on a clean
checkout still passes on the smoke specs rather than producing a wall of misleading failures.

### Reproducing the environment

```bash
docker compose up -d postgres
cd backend && ./mvnw spring-boot:run
```

Then complete setup with the key from `backend/.finora/installation.key`, and — because of
Issue 01 — seed verification before anything authenticated will work:

```sql
UPDATE users SET phone_verified = true WHERE email LIKE 'e2e%';
```

### A note on one false alarm

Four authenticated UI tests initially failed at login. That was **not** a product defect — the
account was still locked by the rate-limiting probe run minutes earlier. Clearing
`failed_login_attempts` and `locked_until` made all seven pass. Recorded because the lockout
working correctly is a positive result that first presented as a failure.

---

## Recommended next steps

1. **Issue 01 first.** It blocks a working local environment for every developer, and it blocks the
   rest of this test plan.
2. **Issue 02 next** — a 79-second import for a year of transactions is a product-level problem,
   not just a performance note.
3. Re-run this plan once Issue 01 is resolved, to cover the admin portal, the merchant learning
   queue, cross-application workflows, and the remaining modules listed as untested above.
4. Add Firefox and WebKit projects plus tablet/mobile viewports to `playwright.config.ts` — the
   config already has the project structure for it; only Chromium was exercised here.
