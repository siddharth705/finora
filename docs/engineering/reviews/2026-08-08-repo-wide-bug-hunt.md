# Repo-Wide Bug Hunt — 2026-08-08

**Scope:** whole repository (backend, frontend, admin portal, mobile, e2e, migrations, CI/CD, deployment config).
**Rules followed:** nothing was fixed. No production code, migration, configuration or test in the
repository was modified during this hunt. All reproduction code was written to a scratch directory
outside the tree and run against `backend/target/classes`.

**Evidence discipline.** Findings marked **Confirmed Bug** are either (a) reproduced by executing the
repository's own compiled classes, with the transcript included below, or (b) traced end-to-end
through the code with the exact call path quoted. Findings that could not be proven to that standard
are filed under **Potential Risk**, **Design Concern**, **Test Gap** or **Performance Concern**, and
say so. Three findings I initially suspected did not survive checking and are recorded under
**Rejected Findings** so nobody re-investigates them.

---

> **Remediation status (updated 2026-08-09).** Batches 1 and 2 are fixed on
> `fix/bug-hunt-remediation-batch-1`. Fixed: BH-001, BH-002, BH-003, BH-004, BH-005, BH-008,
> BH-009, BH-010, BH-011, BH-012, BH-013, BH-020, BH-021, BH-022, BH-026, and the tractable part
> of BH-042. Partially addressed: BH-015 (rate-limited; the disclosure itself needs a flow change
> — see [Remediation notes](#remediation-notes)). Everything else is untouched. Each fixed finding's section is unchanged so the original evidence
> stays readable; the notes at the end record what was done, what was deliberately not done, and
> one fix that was reverted because it would have broken production.

## Contents

- [Executive Summary](#executive-summary)
- [Top 10 Findings](#top-10-findings)
- [Confirmed Bugs](#confirmed-bugs) (BH-001 … BH-030)
- [Potential Risks](#potential-risks) (BH-031 … BH-040)
- [Design Concerns](#design-concerns) (BH-041 … BH-047)
- [Test Gaps](#test-gaps) (BH-048 … BH-054)
- [Performance Concerns](#performance-concerns) (BH-055 … BH-057)
- [Rejected Findings](#false-positives--rejected-findings)
- [Areas Reviewed](#areas-reviewed)
- [Areas Not Fully Reviewed](#areas-not-fully-reviewed)
- [Reproduction Harnesses](#appendix--reproduction-harnesses)

---

## Executive Summary

| Metric | Count |
|---|---|
| **Total open findings** | **56** |
| Critical | 1 |
| High | 12 |
| Medium | 24 |
| Low | 18 |
| Informational | 1 |
| — | — |
| Confirmed Bugs | 30 |
| Potential Risks | 9 |
| Design Concerns | 7 |
| Test Gaps | 7 |
| Performance Concerns | 3 |
| *(subtotal — the five categories above)* | *56* |
| Investigated and closed (false positive / not a defect) | 4 |
| **Total items investigated** | **60** |

**Cross-cutting themes.** A finding can appear in more than one row; IDs are listed so the tallies are
checkable rather than asserted.

| Theme | Count | Finding IDs |
|---|---|---|
| Security | 14 | BH-011, 012, 013, 014, 015, 017, 031, 032, 033, 035, 036, 037, 038, 039 |
| Financial correctness | 10 | BH-001, 003, 004, 005, 006, 007, 019, 023, 026, 027 |
| Performance / scalability | 12 | BH-024, 025, 030, 041, 042, 043, 044, 045, 046, 055, 056, 057 |
| Concurrency | 9 | BH-001, 002, 013, 019, 020, 034, 038, 047, 053 |
| Test / infrastructure & docs-vs-code | 9 | BH-021, 022, 048, 049, 050, 051, 052, 053, 054 |

**Headline.** The two most serious findings are both in code written in the last two weeks — the
asynchronous import queue (`ImportJobWorker` / `ImportJob`) — and both were reproduced by running the
repository's own compiled state machine. Behind them sits a cluster of **financial-correctness**
defects in the confirm/reconcile path that are older, quieter, and arguably worse: an account balance
that double-counts a re-imported statement, a closing-balance guard that is blind to the credit-card
sign convention, and a refund treatment that reports a loss for a month in which nothing was spent.
None of those three has a test, and all three are invisible to the user because the number they
corrupt is one nobody can eyeball.

The codebase's documentation is unusually rich and mostly accurate. Where it is wrong, it is wrong in
a specific and dangerous way: several doc comments assert a safety property the code next to them does
not have (BH-021, BH-022, BH-018). Those are called out individually, because a comment claiming a
guarantee is worse than silence — it stops the next reader checking.

---

## Top 10 Findings

Ranked by business/security impact, not by severity label alone.

| # | ID | Finding | Why it ranks here |
|---|---|---|---|
| 1 | BH-001 | Cancelling an import that a worker is holding silently **un-cancels** it | The user pressed Stop, the system said "cancelled", and the import runs anyway. Reproduced. |
| 2 | BH-003 | Re-importing (or re-uploading) a statement **moves the account balance twice** | Every derived figure — net worth, health score, low-balance alert — is permanently wrong, and reconciliation's duplicate flag hides the evidence. Reproduced. |
| 3 | BH-004 | `ClosingBalanceGuard` uses the asset formula for **every** account type | Every correct credit-card statement is reported to the user as not adding up, and its authoritative closing balance is never applied. Reproduced. |
| 4 | BH-005 | A refunded purchase is reported as a **pure loss** | Dashboard and Reports drop the refund income but keep the expense. Reproduced. |
| 5 | BH-011 | `POST /api/v1/import/jobs` has **no rate limit** | The endpoint the web app now uses for uploads is the one endpoint the import limiter does not cover; each call writes an object to R2 and a queue row. |
| 6 | BH-012 | The refresh token is still in `localStorage` | The HttpOnly cookie work is inert against the threat it was built for. Self-documented in `client.ts`. |
| 7 | BH-013 | Two browser tabs refreshing at once **sign the user out of every device** | Client-side race trips server-side theft detection. Per-tab in-flight guard cannot see other tabs. |
| 8 | BH-002 | A job that kills its worker **never dead-letters** | `markClaimed()++` and `returnToQueue()--` cancel out. Infinite crash loop, no admin visibility. Reproduced. |
| 9 | BH-006 | `confirmReimport` accepts arbitrary client rows with **no staged-row check** | The one confirm path with no validation at all; combined with #2 it is the easiest way to corrupt a balance. |
| 10 | BH-017 | Statement bytes are **never deleted** from object storage | The 48-hour retention `ImportSessionService` documents stops being true the moment a provider is configured. Bank statements, indefinitely. |

---

## Confirmed Bugs

### BH-001 — Cancelling an in-flight import job returns it to the queue and it completes anyway

- **Severity:** Critical
- **Area:** Async import / job queue / concurrency
- **File:** `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java:227-256`, `backend/src/main/java/com/finora/entity/ImportJob.java:172-203`

**Description.** `ImportJob.complete()` refuses to overwrite a cancellation by throwing
`IllegalStateException`. `ImportJobWorker.runOne` catches `ImportJobCancelledException` first and
everything else second — and `IllegalStateException` is not `ImportJobCancelledException`, so the
generic branch runs `recordFailure()`, which sets the job back to `QUEUED`.

**Why it is a bug.** The Javadoc on `ImportJob.complete` states the opposite of what happens:

> `ImportJobWorker` treats this as the cancellation it is rather than as a failure; anything else genuinely is a bug in the caller.

It does not. It treats it as a failure, and `recordFailure` unconditionally writes
`this.status = Status.QUEUED` (line 199) for any job under `MAX_ATTEMPTS`. The next poll re-claims it,
`abortIfCancelled` now sees `QUEUED` rather than `CANCELLED`, and the job runs to completion.

**Steps to reproduce.** Executed against `backend/target/classes` (full harness in the appendix):

```
after claim:    status=PARSING attempts=1
after advance:  status=ANALYZING
after cancel:   status=CANCELLED
complete() threw: IllegalStateException
is it an ImportJobCancelledException? false
RESULT after recordFailure: status=QUEUED  deadLettered=false  nextAttemptAt=2026-08-08T17:52:46Z
>>> CANCELLED job was returned to the QUEUE. It will be re-claimed and will produce a staged session.
```

The race window is between the worker's second `abortIfCancelled(jobId)` (line 225) and its
`jobStore.update(... j.complete(...))` (line 227). `ImportJobService.cancel` commits in a separate
transaction, so any cancel landing in that window hits it.

- **Expected behavior:** a cancelled job stays `CANCELLED` and produces no staged session.
- **Actual behavior:** the job returns to `QUEUED`, is re-claimed, and hands the user the staged import they asked to stop.
- **Impact:** the Cancel button's stated promise ("no session is created and nothing reaches the user's ledger" — `ImportJobService.cancel` Javadoc) is not kept. A user who cancels the wrong file gets it staged anyway and may confirm it.
- **Financial impact:** the resurrected job stages a document the user explicitly rejected. If confirmed, its rows land in the ledger and move the account balance.
- **Evidence:** transcript above; `ImportJobWorker.java:240` (`catch (ImportJobCancelledException)`) vs `ImportJob.java:174` (`throw new IllegalStateException`).
- **Recommended direction:** have the worker recognise the cancelled state explicitly rather than relying on exception type — e.g. `complete()` returning a result, or `recordFailure` refusing to move a `CANCELLED` job. Note that `recordFailure` writing `QUEUED` over a terminal state is the wider defect: it can also resurrect a `FAILED` job.

---

### BH-002 — A job that crashes its worker never dead-letters (infinite retry)

- **Severity:** High
- **Area:** Async import / job queue
- **File:** `backend/src/main/java/com/finora/entity/ImportJob.java:129-134` (`markClaimed`), `:217-223` (`returnToQueue`)

**Description.** `markClaimed` increments `attemptCount`. `returnToQueue` — used by
`ImportJobStore.recoverAbandoned` — decrements it. A job whose parse reliably kills the worker (an
OOM on a large PDF, a `StackOverflowError` in `PdfTableLocator`) cycles claim → crash → recover →
claim forever, at a net attempt count of zero.

**Steps to reproduce:**

```
poison pill after 12 crash/recover cycles: status=QUEUED attempts=0 (MAX_ATTEMPTS=5)
>>> never dead-letters: markClaimed()++ and returnToQueue()-- cancel out.
```

- **Expected behavior:** after `MAX_ATTEMPTS` the job becomes `FAILED` and surfaces in the admin queue.
- **Actual behavior:** it recycles indefinitely, every 30 minutes (`IN_FLIGHT_TIMEOUT`), consuming a claim slot and a worker each time.
- **Impact:** a stuck job is permanently invisible to the dead-letter runbook. Combined with `BATCH_SIZE = 10`, a handful of poison documents can occupy the whole batch on every pass and starve real work.
- **Evidence:** transcript above.
- **Recommended direction:** `returnToQueue` should not decrement. If not charging an attempt is the intent, track recoveries in their own column so the two counters cannot cancel.

---

### BH-003 — Importing the same statement twice moves the account balance twice

- **Severity:** High · **Financial**
- **Area:** Import confirm / balance calculation / reconciliation
- **File:** `backend/src/main/java/com/finora/imports/ImportService.java:763-775`, `backend/src/main/java/com/finora/service/ReconciliationService.java:110-135`

**Description.** When the closing balance is not corroborated (which is the common case — see BH-004,
and any statement with an excluded row or no stated opening balance), `confirm()` applies
`AccountBalanceConvention.netDelta(...)` to `Account.balance`. Reconciliation then runs and flags the
newly inserted rows `DUPLICATE`, which excludes them from `DashboardService`/`ReportService` totals —
**but nothing reverses the balance movement they already caused.**

**Steps to reproduce:**

```
after first confirm : balance = 4000.00 (net -1000.00) -- correct
after second confirm: balance = 3000.00 -- the duplicate rows are excluded from dashboard totals, the balance is not
```

- **Expected behavior:** a duplicate import leaves `Account.balance` where it was, or the duplicate rows' contribution is reversed when they are flagged.
- **Actual behavior:** `Account.balance` moves by the statement's full net effect a second time; the ledger view hides the rows that caused it.
- **Impact:** `Account.balance` is read by net worth, the dashboard's liquid/assets/liabilities tiles, the health score's debt-utilisation component and the low-balance notification threshold. Nothing recomputes it, so the disagreement is permanent and silent — exactly the failure mode `ClosingBalanceGuard`'s own class comment says it exists to prevent.
- **Financial impact:** for the reproduced credit card, a duplicate import understates the outstanding balance by ₹1,000 forever. Direction and magnitude depend on the statement's net.
- **Evidence:** transcript above; `ImportService.java:763` (`else if (!toInsert.isEmpty())` branch) and `ReconciliationService.java:110-135`, which sets flags and never touches `Account`.
- **Recommended direction:** decide whether duplicate-flagged rows contribute to `Account.balance` and make one owner enforce it. If they must not, reconciliation has to reverse their delta when it flags them.

---

### BH-004 — `ClosingBalanceGuard` applies the asset formula to liability accounts

- **Severity:** High · **Financial**
- **Area:** Import confirm / balance calculation
- **File:** `backend/src/main/java/com/finora/imports/ClosingBalanceGuard.java:97-149`

**Description.** `assess()` takes no account type and always computes
`opening + credits − debits == closing`. For a `CREDIT_CARD`, `Account.balance` is money **owed**, so
the correct arithmetic is `opening + debits − credits` — the very inversion
`AccountBalanceConvention` was created to stop being re-derived by hand. The guard is the fourth
place that re-derives it, and it derives it wrongly.

**Steps to reproduce.** A realistic card statement: opening outstanding ₹5,000, a ₹2,000 purchase
(EXPENSE), a ₹3,000 bill payment (INCOME), issuer-printed closing outstanding ₹4,000.

```
verdict = UNCORROBORATED
reason  = The imported transactions do not reach the statement's closing balance (off by 2000.00).
guard computed expectedClosing = 6000.00 (asset formula: opening + credits - debits)
the card's real closing        = 4000.00 (liability formula: opening + debits - credits)
```

- **Expected behavior:** `CORROBORATED` — the rows do reach the printed closing balance.
- **Actual behavior:** `UNCORROBORATED` on every arithmetically perfect credit-card statement, off by exactly `2 × (credits − debits)`.
- **Impact:** two consequences. (1) The user is told, in a warning surfaced on the import summary, that their statement does not add up — for a statement that does. (2) The statement's authoritative closing balance is **never** written to a credit-card account, so card balances always fall through to the `netDelta` branch, which is the branch that double-counts (BH-003).
- **Financial impact:** card balances are derived rather than stated, and become permanently wrong on any duplicate or partial import.
- **Evidence:** transcript above. `AccountBalanceConvention.isLiability` exists and is not consulted; `ClosingBalanceGuard.assess`'s signature has no account-type parameter.
- **Recommended direction:** pass the account type into `assess` and select the formula from `AccountBalanceConvention`, so the corroboration check and the balance write agree on one convention.

---

### BH-005 — A refunded purchase is reported as a pure loss

- **Severity:** High · **Financial**
- **Area:** Reports / dashboard / reconciliation
- **File:** `backend/src/main/java/com/finora/service/DashboardService.java:56-60`, `backend/src/main/java/com/finora/service/ReportService.java:40-43`, `backend/src/main/java/com/finora/service/ReconciliationService.java:225-233`

**Description.** The refund pass sets `ReconciliationStatus.REFUND` on the **INCOME** leg only; the
matched EXPENSE keeps `OK`. Both the dashboard and the monthly report filter out `REFUND` rows. So
the refund is removed from income and the purchase stays in expenses.

**Steps to reproduce:**

```
dashboard expenses = 500.00, dashboard income = 0, dashboard net = -500.00
actual money moved = 0.00 (and Account.balance moved by exactly that, since both legs were applied)
>>> the dashboard reports a 500.00 loss for a month in which nothing was spent
```

- **Expected behavior:** a fully refunded purchase nets to zero in the reporting period, or the expense leg is excluded alongside the income leg.
- **Actual behavior:** monthly expenses are overstated by the refunded amount and monthly net is understated by it; `Account.balance` (which applied both legs) disagrees with the dashboard by the same figure.
- **Impact:** `savingsRate`, `pct(...)` month-over-month deltas, `spendByCategory`, the health score's monthly-income input and the Reports page all inherit the error. A user with a returned purchase sees a category over-spend that did not happen.
- **Evidence:** transcript above; the filter is identical in both services and excludes exactly one of the two legs.
- **Recommended direction:** decide the accounting treatment once (net the pair, or exclude both legs) and apply it in one shared filter rather than two copies of a one-sided one. Note the cross-period case — a January purchase refunded in March — needs an explicit answer either way.

---

### BH-006 — `confirmReimport` performs no staged-row validation

- **Severity:** High · **Financial**
- **Area:** Import / API contract
- **File:** `backend/src/main/java/com/finora/service/StatementImportService.java:209-217`

**Description.** `confirmSession` (`ImportService.java:485-509`) claims a session atomically and at
least compares `stagedRows.size()` against `request.rows().size()`. `confirmMultiSection` does the
same per section. `confirmReimport` does neither: it takes the client's `request.rows()` verbatim,
substitutes the original account id, and calls `importService.confirm(...)`.

**Why it is a bug.** There is no session to claim, so there is nothing preventing a double-submit,
and no check at all that the confirmed rows correspond to what the re-import actually staged. Every
field — date, amount, type, description, `balanceAfter`, `referenceNumber` — is whatever the client
sent.

- **Steps to reproduce:** `POST /api/v1/statement-imports/{id}/reimport` to stage, then
  `POST /api/v1/statement-imports/{id}/reimport/confirm` with a rows array bearing no relation to the
  staged output. It is accepted, a new `StatementImport` is created, and the rows are inserted.
- **Expected behavior:** the same claim-and-compare discipline `confirmSession` applies, or an explicit statement of why re-import is exempt.
- **Actual behavior:** unvalidated client rows become ledger entries and move `Account.balance`.
- **Impact:** this is the shortest path to BH-003. A double-clicked "Confirm re-import" inserts the statement twice and moves the balance twice with nothing to stop it.
- **Attack scenario:** self-inflicted only — the rows land in the caller's own account (`OwnershipGuard` covers the statement id and the account is forced from the original row), so this is a data-integrity defect rather than a cross-tenant one. It is still the case that the server's ledger is not derived from the server's parse.
- **Recommended direction:** give the re-import path a staged session, or at minimum the same row-count comparison plus a single-use claim.

---

### BH-007 — One expense can be the refund target of unlimited income rows

- **Severity:** Medium · **Financial**
- **Area:** Reconciliation / duplicate detection
- **File:** `backend/src/main/java/com/finora/service/ReconciliationService.java:196-236`

**Description.** The refund pass iterates every INCOME candidate and, for each, picks a `bestMatch`
EXPENSE. Nothing marks an expense as consumed. The only amount guard is per-pair
(`income.getAmount().compareTo(expense.getAmount()) > 0 → skip`), so N income rows each ≤ the expense
can all point at the same expense.

- **Steps to reproduce:** one ₹500 EXPENSE at merchant "ACME"; two ₹500 INCOME rows at "ACME" in the following week (a refund, and an unrelated payout that happens to match the merchant token). Both satisfy `sameMerchant`, both are ≤ ₹500, both are within 180 days, both get `REFUND`.
- **Expected behavior:** total refunds attributed to one expense cannot exceed that expense.
- **Actual behavior:** ₹1,000 of income is excluded from every total on the strength of a ₹500 purchase.
- **Impact:** income is understated; `savingsRate` and the health score follow. Silent — a `REFUND` row is simply absent from the dashboard.
- **Evidence:** `ReconciliationService.java:229-236` — `bestMatch` is a local per-income variable; no set of already-refunded expense ids exists.
- **Recommended direction:** track consumed expenses within the pass, and cap cumulative refunds per expense at its amount.

---

### BH-008 — `GET /api/v1/import/jobs?limit=0` returns 500

- **Severity:** Low · **Security-adjacent (error handling)**
- **Area:** API contract / async import
- **File:** `backend/src/main/java/com/finora/imports/jobs/ImportJobService.java:161-164`

**Description.** `recent()` clamps only the upper bound: `PageRequest.of(0, Math.min(limit, 50))`.
Spring Data rejects a page size below one, and `IllegalArgumentException` has **no handler** in
`GlobalExceptionHandler`, so it reaches the `Exception` catch-all.

**Steps to reproduce** (run against the project's own `spring-data-commons`):

```
GET /api/v1/import/jobs?limit=0 -> java.lang.IllegalArgumentException: Page size must not be less than one
```

- **Expected behavior:** 400, or a clamp, as `com.finora.util.PageBounds` already does everywhere else.
- **Actual behavior:** 500 `INTERNAL_ERROR`, logged as `log.error("Unhandled exception ...")`.
- **Impact:** low functionally; the real cost is alert noise — routine bad input is logged at the same level as a genuine server fault, which is the exact defect `GlobalExceptionHandler`'s own comments describe fixing four separate times.
- **Recommended direction:** route through `PageBounds.safeSize`, and add an `IllegalArgumentException` handler so the class of bug is closed rather than the instance.

---

### BH-009 — Unvalidated `sortDir` on transaction search returns 500

- **Severity:** Low
- **Area:** Transactions / API contract
- **File:** `backend/src/main/java/com/finora/transactions/TransactionService.java:82-83`

**Description.** The same method that carefully clamps `page` and `size` (with a comment explaining
that an unclamped value "500'd on a malformed page param") passes `f.sortDir()` straight into
`Sort.Direction.fromString`.

```
POST /transactions/search {sortDir:"bogus"} -> java.lang.IllegalArgumentException: Invalid value 'bogus' for orders given; Has to be either 'desc' or 'asc' (case insensitive)
```

- **Expected/Actual:** 400 expected; 500 `INTERNAL_ERROR` actual.
- **Impact:** same class as BH-008 — the fix was applied to two of three unvalidated inputs in one method.
- **Recommended direction:** `Sort.Direction.fromOptionalString(...).orElse(DESC)`.

---

### BH-010 — Oversized uploads return 500 instead of 413

- **Severity:** Medium
- **Area:** File upload / error handling
- **File:** `backend/src/main/java/com/finora/exception/GlobalExceptionHandler.java:229-239`, `backend/src/main/resources/application.yml` (`spring.servlet.multipart.max-file-size: 10MB`)

**Description.** There is no `@ExceptionHandler(MaxUploadSizeExceededException.class)`. The
`@ExceptionHandler(Exception.class)` catch-all in a `@RestControllerAdvice` is consulted before
Spring's `DefaultHandlerExceptionResolver` — the handler class's own comment explains this mechanism
for three other exception types — so an over-limit upload becomes a 500 with `INTERNAL_ERROR`.

- **Steps to reproduce:** `POST /api/v1/import/pdf/stage` with an 11 MB PDF.
- **Expected behavior:** 413 (or 400) naming the size limit.
- **Actual behavior:** 500 `Unexpected error`, logged as an unhandled exception. The user is told the server broke; the real answer is "your statement is too big".
- **Impact:** a plausible, ordinary user action (a year of statements as one PDF) reads as a product outage and pages on the error-rate alert.
- **Recommended direction:** add the handler alongside the other four client-input handlers already in the class.

---

### BH-011 — The async upload endpoint has no rate limit

- **Severity:** High · **Security**
- **Area:** Async import / rate limiting
- **File:** `backend/src/main/java/com/finora/config/RateLimitFilter.java:220-229`, `backend/src/main/java/com/finora/controller/ImportJobController.java:75-87`

**Description.** `limitedEndpoints` covers `/api/v1/import/csv/stage` and `/api/v1/import/pdf/stage`.
It does **not** cover `/api/v1/import/jobs`, the endpoint the web frontend now uses
(`frontend/src/components/ImportProgress.tsx`, `frontend/src/api/endpoints.ts:405`).

**Why it is a bug.** `importStageLimiter`'s own justification is written in the filter:

> every call writes a real row to `import_sessions` INCLUDING the raw file bytes, bounded only by a 48h TTL … Unprotected, this endpoint became a way to grow that table indefinitely.

The async endpoint does strictly more per call: it writes a full object to R2 **and** a queue row, and
the resulting job then writes an `import_sessions` row with the bytes as well. `ImportJobController`'s
comment explains why the *concurrency limiter* is deliberately skipped; it says nothing about the rate
limiter, which suggests the omission is an oversight rather than a decision.

- **Steps to reproduce:** authenticate, then `POST /api/v1/import/jobs` in a loop with a 10 MB PDF. No 429 is ever returned; the poller drains the queue and each job writes a session.
- **Expected behavior:** the same 10-per-10-minutes ceiling the synchronous staging endpoints carry.
- **Actual behavior:** unbounded, per authenticated user.
- **Attack scenario:** one compromised or malicious account uploads continuously. Because content addressing dedupes identical bytes, an attacker varies one byte per request; each distinct upload is a new R2 object that **is never deleted** (BH-017) plus a queue row plus a staged session with the bytes in Postgres. Cost and storage growth are unbounded and the only ceiling is `max-file-size`.
- **Recommended direction:** add `/api/v1/import/jobs` to `limitedEndpoints`, sharing `importStageLimiter`.

---

### BH-012 — The refresh token is written to `localStorage`, defeating the HttpOnly cookie

- **Severity:** High · **Security**
- **Area:** Frontend auth / session management
- **File:** `frontend/src/api/client.ts:42-47`, `:186-187`

**Description.** `RefreshTokenCookie` issues the refresh token as `HttpOnly; Secure; SameSite=Lax`
scoped to `/api/v1/auth`, and its class comment names the reason: "the single largest reduction in
blast radius available for an XSS on a page that shows bank statements." The web client then writes
the same token to `localStorage` (`safeStorage.setItem('finora_refresh_token', ...)`), where script
can read it.

The code says so itself, at `client.ts:42`:

> This restores the cookie transport; it does NOT on its own deliver the XSS mitigation the cookie exists for. `AuthContext.persist` still writes the same refresh token to localStorage, where script can read it.

- **Expected behavior:** the durable credential is unreadable by page script.
- **Actual behavior:** it is readable by any script that executes on the origin.
- **Impact:** an XSS on the user app yields a 30-day, rotating refresh token — full account takeover that survives the victim closing the tab. The mitigation exists, is deployed, and is bypassed by the app itself.
- **Attack scenario:** any stored/reflected XSS (or a compromised npm dependency — the app ships 18 accepted advisories, see `scripts/check-dependency-advisories.py`) reads `localStorage.finora_refresh_token` and exfiltrates it. The HttpOnly cookie is irrelevant because the value is available in plaintext beside it.
- **Note:** mobile is **not** affected — `mobile/src/lib/safeStorage.ts` uses `expo-secure-store` (Keychain/Keystore). This is a web-only gap.
- **Recommended direction:** stop persisting the refresh token on web; rely on the cookie, and keep the body transport for mobile only.

---

### BH-013 — Two browser tabs refreshing simultaneously sign the user out of every device

- **Severity:** High · **Security / availability**
- **Area:** Frontend auth / session management
- **File:** `frontend/src/api/client.ts:146-158`, `backend/src/main/java/com/finora/service/RefreshTokenService.java:118-122`

**Description.** `refreshInFlight` de-duplicates concurrent refreshes **within one JavaScript
context**. Two tabs of the same app are two contexts with two module instances. Both read the same
`finora_refresh_token` from shared `localStorage`, both call `/auth/refresh`, one wins, the other
presents an already-rotated token — and `RefreshTokenService.rotate` treats that as theft and calls
`revokeAllForUser`.

**Why it is a bug.** The mitigation's own comment describes exactly this failure and then implements a
guard that cannot see across tabs:

> N requests that happen to 401 around the same moment … each independently called `authApi.refresh()` with the SAME refresh token … force-logging the user out of every device over a client-side race, not actual theft.

- **Steps to reproduce:** open the app in two tabs, leave both idle past the 15-minute access-token expiry, then focus each in quick succession (or simply have both tabs' React Query refetch on window focus). Both 401, both refresh, the loser triggers `AUTH_SESSION_REVOKED`, every session dies.
- **Expected behavior:** ordinary multi-tab use does not look like credential theft.
- **Actual behavior:** the user is signed out on their laptop and their phone, and shown "All sessions have been signed out as a precaution."
- **Impact:** repeated spurious logouts train users to ignore the one message that would matter during a real compromise. It also dulls the reuse-detection signal operationally.
- **Residual single-tab race:** even within one tab, `const refreshToken = safeStorage.getItem(...)` at line 181 is read *before* awaiting; a request whose handler resumes after `refreshInFlight` has been cleared will call refresh with the stale value.
- **Recommended direction:** either coordinate across tabs (BroadcastChannel or a `localStorage` lock), or give the server a short reuse grace window keyed on the successor token — one of the two, not neither.

---

### BH-014 — Account-existence oracle via the lockout response

- **Severity:** Medium · **Security**
- **Area:** Authentication
- **File:** `backend/src/main/java/com/finora/service/AuthService.java:307-310`

**Description.** A locked account returns `423 LOCKED` with "This account is temporarily locked…". A
non-existent identifier returns `401` "Invalid credentials". Five wrong passwords therefore convert
any email address into a yes/no answer about whether an account exists.

The code acknowledges this in a comment at `AuthService.java:371-375` — "It leaks the same way and is
reported rather than papered over here" — which is honest, but it is still an open enumeration
primitive and belongs on this list rather than only in a comment.

- **Steps to reproduce:** `POST /auth/login` five times with `victim@example.com` and a wrong password; the sixth attempt returns 423 if the account exists and 401 if it does not.
- **Impact:** account enumeration on an unauthenticated public endpoint for a financial product. `forgotPassword` and the suspension check were both deliberately hardened against exactly this; the lockout path was not.
- **Rate-limit interaction:** `loginLimiter` is 10 per 60 s per IP, so five attempts per target are cheap, and the per-IP bucket is trivially widened with a proxy pool.
- **Recommended direction:** as the comment says, this needs synthesised lockout state for unknown identifiers — a design, not an edit. Filed so it is tracked as a finding rather than as prose.

---

### BH-015 — A reset link discloses the account's full, unmasked phone number

- **Severity:** Medium · **Security / PII**
- **Area:** Password reset
- **File:** `backend/src/main/java/com/finora/service/AuthService.java:564-577`

**Description.** `POST /auth/reset-password/phone` returns `user.getPhoneNumber()` in full to anyone
presenting a valid, unused reset token. Everywhere else in the codebase a phone number is masked
(`PhoneMasking.mask` is used in both `register` and `login` responses).

- **Steps to reproduce:** obtain a reset token (email access, or BH-031's misconfiguration), call the endpoint, read the E.164 number.
- **Expected behavior:** a masked number, sufficient for the frontend's "we'll text +91 ••••• ••210" affordance.
- **Actual behavior:** the complete number.
- **Impact:** email-account compromise escalates to PII disclosure without completing the reset, and the number is the second factor for the reset itself — knowing it is a prerequisite for a SIM-swap or social-engineering follow-up.
- **Rate limiting:** this endpoint is **not** in `limitedEndpoints`; the filter's comment classifies it as "cheap, no-real-cost" and gates it on the unguessable token. That reasoning is about cost, not about disclosure.
- **Recommended direction:** return the masked form; the frontend does not need the full value.

---

### BH-016 — Email sends happen inside the database transaction

- **Severity:** Medium
- **Area:** Authentication / resource management
- **File:** `backend/src/main/java/com/finora/service/AuthService.java:98-121` (`register`), `:507-553` (`forgotPassword`), `:579-643` (`resetPassword`)

**Description.** All three methods are `@Transactional` and make a synchronous HTTP call to Resend
inside the transaction. `TransactionService.sendTransactionAlert` had this exact bug fixed —
deferred to `afterCommit` via `TransactionSynchronizationManager`, with a comment explaining that "a
slow (or hanging) 2Factor API call held the DB connection for create()'s entire transaction". The
same fix was never applied to the email paths.

- **Steps to reproduce:** make Resend slow (network partition, provider incident). Every registration and every forgot-password request holds one of ten pooled connections for the duration of the HTTP timeout.
- **Expected behavior:** the email is sent after commit, and its failure does not roll back the user.
- **Actual behavior:** connection held across a third-party call; and if `sendWelcomeEmail` throws, `register` rolls back — the user, their 25 seeded categories, their password-history row and their audit entry all disappear, after the refresh token has been minted.
- **Impact:** a provider outage becomes a connection-pool outage for the whole application, not just for signup. `ResendEmailProvider`'s failure behaviour determines whether the rollback case is live; either way the connection-holding is unconditional.
- **Recommended direction:** the `afterCommit` pattern already in `TransactionService`.

---

### BH-017 — Statement bytes are never deleted from object storage

- **Severity:** Medium · **Privacy / retention**
- **Area:** File storage / data retention
- **File:** `backend/src/main/java/com/finora/imports/storage/StatementStorage.java:15-21`, `backend/src/main/java/com/finora/imports/ImportSessionService.java:55-77`

**Description.** `StatementStorage` deliberately exposes no `delete` ("another row may still need
it"), and no sweep exists. Three deletion paths therefore remove the row and leave the bytes:

1. `ImportSessionService.deleteExpiredSessions` — the 48-hour TTL.
2. `StatementImportService.delete` — the user deleting a statement.
3. `users` deletion — `import_jobs.user_id` has `ON DELETE CASCADE`; the object does not cascade.

**Why it is a bug.** `ImportSessionService`'s own doc comment presents the TTL fix as closing a
retention gap:

> The stated 48-hour retention was not enforced for exactly the people it applied to, on bank statements, which is the most sensitive data this product holds.

Once `app.statement-storage.provider` is set, that statement stops being true again — the row goes,
the statement stays. The absence of delete is documented as a design decision; the retention
consequence of that decision is not.

- **Expected behavior:** a statement's bytes become unreachable within the stated retention window, and a deleted user's documents are removed.
- **Actual behavior:** every statement ever uploaded persists in R2 indefinitely, including abandoned sessions from users who never returned.
- **Impact:** an unbounded, growing store of customer bank statements with no lifecycle. This is a data-subject-deletion problem, not only a cost one.
- **Recommended direction:** a reference-counted sweep (the "future sweep" `StatementContentService` already anticipates), or an R2 lifecycle rule keyed on a prefix that only ever holds session content.

---

### BH-018 — `ImportJobService.accept` documents an ordering it does not implement

- **Severity:** Medium
- **Area:** Async import / transaction boundaries / docs-vs-code
- **File:** `backend/src/main/java/com/finora/imports/jobs/ImportJobService.java:26-40`, `:92-127`

**Description.** The class comment states the order of operations as:

> 2. **Store the bytes** — outside the transaction. Object storage cannot participate in one, and holding a database transaction open across a network upload would tie up a connection from a pool capped at 10 for the duration.

The method is annotated `@Transactional` and calls `active.store(file.getBytes())` inside it. The
inline comment at line 109 concedes this and argues it is harmless "because the store call is a
network write that does not touch the database". That holds only while no JDBC statement has been
issued first — Hibernate's delayed connection acquisition. It is not a property of this method; it is
a property of every caller above it and of Hibernate's configuration.

- **Expected behavior:** the R2 upload happens outside any transaction, as documented.
- **Actual behavior:** it happens inside one; whether a connection is held depends on configuration the comment does not name.
- **Also here:** `file.getBytes()` materialises the whole upload (up to 10 MB) on the heap, and the object is then held for the whole R2 upload. `ImportConcurrencyLimiter` does not gate this endpoint (BH-011), so the number of simultaneous 10 MB arrays is bounded only by Tomcat's thread pool.
- **Recommended direction:** split the store out of the transactional method, or delete the claim from the comment. Do not leave both.

---

### BH-019 — No submission idempotency: the same file uploaded twice creates two jobs

- **Severity:** Medium · **Financial**
- **Area:** Async import / idempotency
- **File:** `backend/src/main/resources/db/migration/V67__import_job_idempotency.sql`, `backend/src/main/java/com/finora/imports/jobs/ImportJobService.java:92-127`

**Description.** V67 makes *replay of one job row* safe (`statement_imports.import_job_id` UNIQUE,
`transactions (statement_import_id, row_ordinal)` UNIQUE). Neither constraint addresses the same
document being **submitted** twice: two `POST /import/jobs` calls create two `import_jobs` rows with
the same `content_hash`, and each produces its own `ImportSession`.

- **Steps to reproduce:** double-click Upload, or retry after a timed-out response. Two jobs, two staged sessions, two independent confirms available.
- **Expected behavior:** a re-submission of identical bytes by the same user within a window returns the existing job (202 with the same jobId), matching the idempotency posture the rest of the queue documents.
- **Actual behavior:** two jobs. If the user confirms both, the transactions land twice and `Account.balance` moves twice (BH-003).
- **Impact:** this is the "same job submitted twice" scenario from the review brief, and it is the one the current design does not cover.
- **Recommended direction:** a partial unique index on `(user_id, content_hash)` over non-terminal statuses, or an explicit client-supplied idempotency key.

---

### BH-020 — `ImportJob` has no optimistic locking despite concurrent writers

- **Severity:** Medium · **Concurrency**
- **Area:** Async import
- **File:** `backend/src/main/java/com/finora/entity/ImportJob.java` (no `@Version`), `backend/src/main/java/com/finora/imports/jobs/ImportJobStore.java:110-116`

**Description.** `ImportJob` is one of the entities with no `@Version` field. Two writers touch a job
concurrently by design: the worker (via `jobStore.update`, `REQUIRES_NEW`) and the cancel endpoint
(via `ImportJobService.cancel`, the caller's transaction). Both are read-modify-write.

`Account`, `Transaction`, `Budget`, `RefreshToken`, `PasswordChangeSession`, `StatementImport` and
`MerchantLearningEvent` all carry `@Version` — and `GlobalExceptionHandler` has a dedicated handler
for the resulting conflict. `ImportJob`, the entity whose entire purpose is concurrent claim/mutate,
does not.

- **Expected behavior:** a lost update on the job row is rejected rather than silently applied.
- **Actual behavior:** last write wins. This is the mechanism behind BH-001 — the two writers cannot detect each other, so the conflict surfaces as an exception in business logic rather than as a lock failure.
- **Recommended direction:** add `@Version` and let the existing 409 handler cover the endpoint side; the worker should retry.

---

### BH-021 — `returnToQueue`'s comment misstates what the learning queue does

- **Severity:** Low · **Docs-vs-code**
- **Area:** Async import / documentation accuracy
- **File:** `backend/src/main/java/com/finora/entity/ImportJob.java:210-223` vs `backend/src/main/java/com/finora/service/MerchantLearningEventWorker.java:239-259`

**Description.** `ImportJob.returnToQueue` justifies decrementing `attemptCount` with:

> The learning queue makes the same distinction.

It does not. `MerchantLearningEventWorker.recoverAbandoned` calls
`event.recordFailure("Abandoned in PROCESSING …")`, which **charges** an attempt and moves the event
toward dead-lettering. The two queues behave oppositely, and the import queue's version is the one
that never terminates (BH-002).

- **Impact:** the comment is the stated justification for the behaviour in BH-002; a reader checking whether the decrement is safe is told to look at a precedent that contradicts it.
- **Recommended direction:** resolve the two queues to one policy and correct whichever comment ends up wrong.

---

### BH-022 — `ImportJob.complete`'s comment asserts handling the worker does not have

- **Severity:** Low · **Docs-vs-code**
- **Area:** Async import / documentation accuracy
- **File:** `backend/src/main/java/com/finora/entity/ImportJob.java:162-177`

**Description.** Documented as part of BH-001, listed separately because it is the reason the bug is
hard to see: the entity claims a caller contract ("`ImportJobWorker` treats this as the cancellation
it is rather than as a failure") that `ImportJobWorker` does not implement. Anyone auditing the
cancel path reads this and stops.

- **Recommended direction:** whatever the fix for BH-001 is, this comment must be made true or removed.

---

### BH-023 — `confirmSession` validates only the row **count**

- **Severity:** Medium · **Financial**
- **Area:** Import confirm / API contract
- **File:** `backend/src/main/java/com/finora/imports/ImportService.java:496-504`

**Description.** The check is `stagedRows.size() != request.rows().size()`. Every value that becomes
a ledger row — amount, date, type, description, `referenceNumber`, `balanceAfter`,
`confirmedNotDuplicate` — comes from the request body, not from the staged session the server holds.
The comment describes this as "the cheapest real check", which is accurate but understates it: the
server has the parsed rows and does not compare against them.

- **Expected behavior:** confirmed rows are the staged rows plus the reviewer's permitted edits (category, include/exclude), validated field by field against what the server parsed.
- **Actual behavior:** a client can confirm any N rows for a session that staged N rows.
- **Impact:** the ledger is not derived from the server's own parse. Every downstream financial figure inherits whatever the client sent. Also enables `row.likelyDuplicate() && row.confirmedNotDuplicate()` to be asserted for rows the engine never flagged — the guard at `ImportService.java:614` checks the client's own `likelyDuplicate` flag, not the server's staged verdict.
- **Recommended direction:** compare confirmed rows against staged rows on the immutable fields and take mutable fields (category, include) from the request only.

---

### BH-024 — `isMostRecentStatementForAccount` loads every statement import for the user

- **Severity:** Medium · **Performance**
- **Area:** Import confirm
- **File:** `backend/src/main/java/com/finora/imports/ImportService.java:855-860`

**Description.** On every confirm, this calls
`statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)` — no limit, no projection — and
filters in memory. `StatementImport` carries the `file_content` BYTEA column; whether it is fetched
depends on the mapping's fetch type, and the query has no explicit projection to guarantee it is not.

Also: `si.getAccountId().equals(accountId)` will NPE on any row with a null `account_id`.

- **Impact:** a user with 200 monthly statements pays a full-table read per confirm, inside the confirm transaction, and `confirmMultiSection` pays it once **per section**.
- **Recommended direction:** a single `SELECT MAX(statement_period_end) … WHERE account_id = ? AND id <> ?` query.

---

### BH-025 — Multi-section confirm writes a full copy of the PDF per section

- **Severity:** Medium · **Performance / storage**
- **Area:** Import confirm / storage
- **File:** `backend/src/main/java/com/finora/imports/ImportService.java:672-676`, `:456-470`

**Description.** `confirmMultiSection` loops the per-account `confirm(...)`, and each iteration does
`statementImport.setFileContent(fileContent)` with the whole document. Content addressing dedupes the
**object-storage** copy — the comment says so — but the database dual-write is per row.

- **Steps to reproduce:** confirm an HSBC-style composite statement with three sections and a 9 MB PDF. Three `statement_imports` rows, 27 MB of BYTEA.
- **Expected behavior:** one copy, referenced N times (which is exactly what the content-addressed object gets).
- **Actual behavior:** N copies in Postgres.
- **Impact:** database growth proportional to sections × file size, on the largest column in the schema, for the whole duration of the dual-write phase (which has no scheduled end — `provider` is unset by default and Phase 4 has not landed).
- **Recommended direction:** skip the BYTEA write when an address was recorded, or accept it explicitly and bound it in the migration plan.

---

### BH-026 — Two transactions from the same merchant are always "regularly spaced"

- **Severity:** Medium · **Financial (detection quality)**
- **Area:** Recurring detection
- **File:** `backend/src/main/java/com/finora/service/RecurringService.java:80-100`

**Description.** With `group.size() == 2`, `gaps` has exactly one element, so `avgGap` equals that
element and `Math.abs(g - avgGap) == 0` — `gapRegular` is unconditionally true. The only remaining
filters are `amountConsistent` (±20% + ₹1) and `5 ≤ avgGap ≤ 95`.

- **Steps to reproduce:** two ₹450 expenses at the same merchant, 21 days apart, nothing else. Both are flagged `recurring = true` and reported as a "Monthly" subscription with a predicted next date.
- **Expected behavior:** a minimum of three occurrences before an interval can be called regular — two points define a line, they do not evidence a pattern.
- **Actual behavior:** any two similar charges 5–95 days apart become a subscription.
- **Impact:** false "recurring" badges across the Ledger, and a Recurring page that predicts charges that will not happen. Two coffee purchases three weeks apart become a monthly subscription.
- **Recommended direction:** require `group.size() >= 3` for the interval-regularity branch; the `MARK_SUBSCRIPTION` rule path already covers the "author knows it is a subscription" case with one occurrence.

---

### BH-027 — Manual duplicates are auto-suppressed with no way to un-suppress them

- **Severity:** Medium · **Financial**
- **Area:** Reconciliation / transactions
- **File:** `backend/src/main/java/com/finora/service/ReconciliationService.java:107-135`, `backend/src/main/java/com/finora/imports/ImportService.java:614-616`

**Description.** The duplicate pass groups on `(accountId, txnDate, amount, description)` and flags
every member but the earliest. The escape hatch — `notDuplicateConfirmedAt` — is written from exactly
one place in the codebase: `ImportService.confirm`, the import review screen. There is no endpoint
and no service method that sets it for a manually created transaction.

- **Steps to reproduce:** create two identical transactions by hand (two ₹40 metro fares on the same day, same description). `reconcileForUser` runs on the second `create()` and flags it `DUPLICATE`.
- **Expected behavior:** the user can say "these are two real transactions", as they can during import review.
- **Actual behavior:** the second is permanently excluded from dashboard income/expense, category spend, budgets and reports — with no UI affordance to reverse it. `Account.balance`, however, includes it (BH-003's mirror image).
- **Impact:** understated spend for exactly the transaction shape most likely to repeat legitimately. And the ledger and dashboard disagree by that amount — the same class of defect the smoke suite was written to catch.
- **Secondary (Low):** `earliest` is chosen by `min(createdAt)` over `findByUserId`, which has no `ORDER BY`. The transfer pass sorts its candidates explicitly and documents why; the duplicate pass does not, so with equal `createdAt` (a batched `saveAll` from one import) which row is suppressed depends on Postgres's row order.
- **Recommended direction:** expose "not a duplicate" on the transaction, not only on the import review screen.

---

### BH-028 — Parser crashes are not recorded as failed analyses

- **Severity:** Low · **Observability**
- **Area:** Import / statement analysis
- **File:** `backend/src/main/java/com/finora/imports/ImportService.java:194-199`, `:272-280`

**Description.** Both staging methods wrap their work in `try { … } catch (ApiException e) { analysisRecorder.recordFailed(...); throw; }`. A `RuntimeException` from the parser — an NPE in
`PdfTableLocator`, an `IndexOutOfBoundsException` in column bucketing, an OOM-adjacent failure —
propagates without recording anything.

- **Expected behavior:** the evidence table captures every failed parse, since its stated purpose is to answer "this layout defeated the parser".
- **Actual behavior:** it captures only the failures the pipeline *anticipated*. The unanticipated ones — the ones that most need a fingerprint to find the document again — leave no row.
- **Impact:** `StatementAnalysisReportService`'s failure histogram systematically under-reports the worst failure class. On the async path the job's `last_error` holds the class name, but the fingerprint and diagnostics are lost.
- **Recommended direction:** catch `Exception`, record, rethrow — the recorder already opens its own `REQUIRES_NEW` transaction and swallows its own errors, so this is safe.

---

### BH-029 — Parser selection for queued jobs is filename-only

- **Severity:** Low
- **Area:** Async import
- **File:** `backend/src/main/java/com/finora/imports/jobs/ImportJobService.java:81-85`

**Description.** `formatOf(fileName)` returns PDF iff the lowercased name ends with `.pdf`, else CSV.
The synchronous endpoints validate magic bytes (`StatementUpload.requireReadable`), and
`ImportJobController` does call that — but *against `formatOf`'s own answer*, so the two agree by
construction and neither can catch a mislabelled file. A PDF named `statement.csv` passes
`requireReadable(file, CSV)`? No — that branch rejects PDF magic on the CSV endpoint. But a **CSV named
`statement.pdf`** is rejected as "not a PDF" with a 415, and a file with neither extension is treated
as CSV.

- **Impact:** low. The failure is a clear 415 at upload rather than a mis-parse minutes later, which is what the design intended. Recorded because the *stored* `sourceFormat` (`ImportService.java:658`) is derived the same way and is what `reimport` routes on — so a statement whose name lost its extension somewhere re-imports through the wrong parser.
- **Recommended direction:** persist the format decided at upload on the `import_jobs` row rather than re-deriving it from the name in two places.

---

### BH-030 — The rate limiter's eviction sweep can never run on a quiet limiter

- **Severity:** Low
- **Area:** Rate limiting / memory
- **File:** `backend/src/main/java/com/finora/config/RateLimiter.java:36-58`

**Description.** `evictExpired` runs when `callCount % 1000 == 0`, and `callCount` is per-`RateLimiter`
instance. `resetPasswordLimiter` and `passwordChangeLimiter` are unlikely to see 1,000 calls in a
deployment's life, so their `windows` maps accumulate one entry per distinct IP and never shrink.

- **Impact:** small and slow — each entry is a short string plus a record. Recorded because the fix comment claims the leak is closed ("Bug fix: `windows` had no eviction at all") and it is closed only for high-traffic limiters. The busiest limiters, which are the ones that mattered, are fine.
- **Recommended direction:** sweep on a time interval rather than a call count, or share one counter across limiters.

---

## Potential Risks

### BH-031 — The entire production hardening layer is gated on one environment variable

- **Severity:** High · **Security** · *Potential Risk (depends on deployment)*
- **File:** `backend/src/main/java/com/finora/config/ProductionConfigValidator.java:130-132`, `backend/src/main/resources/application.yml` (`spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}`)

`validate()` returns immediately unless the `prod` profile is active, and the profile defaults to
`dev`. A deployment that sets real database credentials but forgets `SPRING_PROFILES_ACTIVE=prod`
gets, simultaneously: the placeholder `JWT_SECRET` accepted (anyone with the repo can mint a token for
any user id); `RESEND_API_KEY` unchecked, so `forgotPassword` returns `devResetLink` — **the live reset
link — directly in the HTTP response body** to an unauthenticated caller who knows a victim's email;
Swagger served anonymously (`SecurityConfig.apiDocsPubliclyReachable`); and
`GlobalExceptionHandler.handleGeneric` echoing `ex.getMessage()` to clients.

The validator's own comment identifies the reset-link case as "a full account-takeover primitive". Its
protection is real, and it is entirely conditional on a variable with a permissive default. I could
not verify how Railway is configured, so this is filed as a risk rather than a bug — but the blast
radius justifies checking today.

**Recommended direction:** fail closed — refuse to start when a real (non-localhost) `DB_HOST` or a
non-empty `PORT` is present and the profile is `dev`; or invert the default.

---

### BH-032 — The DB password check only rejects one literal

- **Severity:** Low · *Potential Risk*
- **File:** `ProductionConfigValidator.java:171-175`

`DEFAULT_DB_PASSWORD.equals(dbPassword)` catches `"finora"` and nothing else. The JWT check next to it
was deliberately generalised from an equality test to a marker scan after exactly this reasoning
("Enumerating placeholders is still an enumeration"); the database password kept the equality test.

---

### BH-033 — Placeholder markers can reject a legitimate secret

- **Severity:** Informational · *Potential Risk*
- **File:** `ProductionConfigValidator.java:88-103`

`PLACEHOLDER_MARKERS` includes short, ordinary substrings — `sample`, `dummy`, `example`, `insecure`.
A randomly generated secret containing any of them as a substring is rejected at startup with a
message asserting it is a placeholder. Unlikely per deploy, and the failure is loud and recoverable —
but it fails a *correct* configuration, and the operator's most likely response is to regenerate
rather than to suspect the guard.

---

### BH-034 — Two single-instance controls in a design that is moving to multiple instances

- **Severity:** Medium · *Potential Risk*
- **File:** `config/RateLimiter.java`, `imports/ImportConcurrencyLimiter.java`, `imports/storage/FilesystemStatementStorage.java`

All three are explicitly documented as single-instance. Meanwhile `ImportJobService`'s comment
describes the target topology — "phase 6 of the design splits the API and the workers into separate
services" — and `ImportJobRepository` is built around `SKIP LOCKED` precisely so multiple workers can
run. The moment a second instance exists, every per-IP rate limit doubles, the import concurrency cap
doubles, and `filesystem` storage silently loses documents. The documentation records each caveat
separately; nothing records that the roadmap invalidates all three at the same moment.

---

### BH-035 — `X-Forwarded-For` last-hop trust assumes exactly one proxy

- **Severity:** Low · *Potential Risk*
- **File:** `config/ClientIpResolver.java:36-46`

Taking `hops[hops.length - 1]` is correct for exactly one trusted proxy. With two hops (a CDN in front
of Railway's edge, which is the documented Cloudflare + Railway topology), the last entry is the CDN's
egress IP and every user again shares one rate-limit bucket. The comment's reasoning is right; the
implementation encodes a hop count nobody asserts.

---

### BH-036 — CORS forbids the correlation header the app advertises

- **Severity:** Low · *Potential Risk*
- **File:** `config/CorsConfig.java` (`setAllowedHeaders(List.of("Authorization", "Content-Type"))`), `config/CorrelationIdFilter.java:29-33`

`CorrelationIdFilter` documents that a correlation ID is "reused from the client's `X-Request-Id`
header if present (so a frontend or gateway can propagate its own ID)". A browser cannot send that
header cross-origin — the preflight would fail — because CORS allows only two request headers. No
client currently sends it (verified by grep across all three apps), so this is latent: the propagation
path is documented, unusable from the browser, and would fail confusingly the first time someone tried
it.

---

### BH-037 — Postgres is published on all interfaces in the dev stack

- **Severity:** Low · *Potential Risk*
- **File:** `docker-compose.yml` (`ports: - "5432:5432"`)

`finora/finora` on `0.0.0.0:5432`. Dev-only, but developer machines run on café and conference
networks, and this database holds whatever statements were used for testing. `127.0.0.1:5432:5432`
costs nothing.

---

### BH-038 — Refresh-token reuse detection has no grace window

- **Severity:** Medium · *Potential Risk*
- **File:** `service/RefreshTokenService.java:118-122`

Independently of BH-013, a refresh whose **response** is lost (mobile network drop, proxy timeout)
leaves the client holding a token the server has already rotated. The retry is indistinguishable from
theft and revokes every session. `RefreshToken` carries `@Version`, so the concurrent-write case is
handled; the lost-response case is not. Mobile networks make this routine rather than exotic.

---

### BH-039 — Content-addressed storage is global across tenants

- **Severity:** Low · *Potential Risk*
- **File:** `imports/storage/ContentAddress.java:113-119`

The object key is `SHA-256(content)` with no tenant prefix, so two users who upload the same document
share one object. That is correct and intended today because nothing deletes. It becomes a
cross-tenant defect the moment the "future sweep" `StatementContentService` anticipates is built and
reference counting is not per-object-global. Worth recording now, while the sweep is still a design.

---

*(BH-040 was investigated here and closed — see [RJ-04](#false-positives--rejected-findings).)*

---

## Design Concerns

### BH-041 — Reconciliation and recurring detection run synchronously on every write, over the full history

- **Severity:** High · **Performance / scalability**
- **File:** `transactions/TransactionService.java:227,335,439,454`, `imports/ImportService.java:809-814`, `service/StatementImportService.java:289-296`

Every transaction create, update, delete, bulk delete, import confirm and statement delete calls
`reconcileForUser` **and** `detectForUser`, each of which loads the user's entire transaction history
(`transactionRepository.findByUserId(userId)`, no bound). Two full-history loads, a sort, an O(n·w)
pair scan and a batch write, on the request thread, inside the transaction.

`ReconciliationService` measures itself and warns past one second, and `scaling-triggers.md` records
~450 ms at 10k transactions and 8.4 s at 50k for the pre-optimisation scan. The windowed lookups fixed
the inner loop; the unbounded load and the synchronous placement remain.

**At scale:** one user importing a 5,000-row statement runs this over a history that just grew by
5,000 rows. `confirmMultiSection` runs it **once per section**. 1,000 concurrent users each doing this
exhausts a 10-connection pool long before it exhausts CPU.

**Recommended direction:** this is the natural second customer for the queue infrastructure that
already exists — the same claim/retry/dead-letter shape as `merchant_learning_events`.

---

### BH-042 — Eight services each load the user's entire transaction history

- **Severity:** Medium · **Performance**
- **Files:** `DashboardService:58`, `InsightsService:53`, `AnalyticsService:226`, `WorkspaceDashboardService:76`, `RecurringService:58`, `ReconciliationService:74`, `ReportService:69`, `RelationshipService:204`

None takes a date bound or a limit; all filter in memory. A single dashboard page load fans out across
several of them. `ReportService.availableMonths` loads every transaction to produce a list of month
strings — a `SELECT DISTINCT date_trunc('month', txn_date)` would do it in the database.

**Ask the brief's question:** at 1 user this is invisible; at 1,000 it is a hot database; at 50,000
concurrent it is heap exhaustion, because each request holds its user's whole history as materialised
JPA entities simultaneously.

---

### BH-043 — The import concurrency limiter converts a database bottleneck into a thread bottleneck

- **Severity:** Medium · **Performance**
- **File:** `imports/ImportConcurrencyLimiter.java:78-101`

`runGated` blocks the Tomcat request thread on a fair semaphore for up to
`acquire-timeout-ms` (20 s). With `max-concurrent: 6` and Tomcat's default 200 threads, a burst of 200
uploads leaves 194 request threads parked for 20 seconds. Those threads serve every other endpoint
too, so a burst of imports degrades login, the dashboard and the ledger — the outcome the limiter's
own comment says it exists to prevent ("import processing alone can never starve every other
endpoint"). It protects the connection pool and spends the thread pool instead.

The async path (`/import/jobs`) is the right answer and already exists; the synchronous endpoints are
still the ones mobile and the admin portal use.

---

### BH-044 — `audit_logs` grows without bound and carries financial data

- **Severity:** Medium · **Privacy / storage**
- **File:** `service/AuditService.java`, `service/ReconciliationService.java:265-273`, `transactions/TransactionService.java:441-443`

Every reconciliation run writes a `RECONCILIATION_RUN` row — so every transaction create, update and
delete writes at least two audit rows. There is no retention policy, no partitioning and no archival
anywhere in the schema or the migrations.

The metadata is JSONB and carries customer financial data: `TRANSACTION_DELETED` records `amount` and
the full `description`; `BUDGET_UPSERTED` records the limit. That is defensible for an audit trail,
but it means `audit_logs` is a second, unbounded, indefinitely-retained copy of ledger content
readable by any admin with `AUDIT_VIEW`.

---

### BH-045 — Statement bytes are held whole in memory at four layers

- **Severity:** Medium · **Performance**
- **Files:** `ImportService.java:149,226`, `ImportJobService.java:112`, `ImportJobWorker.java:291-302`, `R2StatementStorage.java:187-194`

`file.getBytes()`, `byte[] content = storage.retrieve(...)`, `RequestBody.fromBytes(content)`,
`ByteArrayInputStream(fileContent)` — every hop is a full-document `byte[]`. With
`max-file-size: 10MB`, six concurrent imports plus a worker batch of ten is on the order of 160 MB of
transient arrays before PDFBox's own working set. `MaxRAMPercentage=75` on a small Railway plan makes
that a real ceiling, and the failure mode is an OOM that takes down every request, not just the
imports.

---

### BH-046 — The dual write to `statement_imports.file_content` has no end date

- **Severity:** Low
- **File:** `imports/storage/StatementContentService.java:24-28`, `application.yml` (statement-storage block)

Phase 2 is documented as temporary ("until Phase 3 has backfilled and Phase 4 has dropped the
column"), and the config comment then records that "the migration/backfill phase was removed once it
was established there is no historical statement content to migrate." So Phase 3 is gone and Phase 4
has no trigger. The dual write is now the steady state, which combined with BH-025 means every
statement is stored `1 + sections` times.

---

### BH-047 — Session cleanup runs inside an unrelated user's upload transaction

- **Severity:** Low · **Concurrency**
- **File:** `imports/ImportSessionService.java:74-77`, called from `createSession`/`createMultiSection`

`deleteExpiredSessions()` deletes up to 50 expired rows belonging to *any* user, inside the acting
user's `@Transactional` create. Two users importing simultaneously can select overlapping batches
(the query is `ORDER BY expiresAt` with no locking) and contend on the same rows; and one user's
upload can now fail on someone else's cleanup. The batch bound and the index are right; the
transaction boundary is the acting user's, which is the wrong owner for a housekeeping task.

---

## Test Gaps

### BH-048 — The full end-to-end suite never runs in CI

- **Severity:** High · **Test infrastructure**
- **File:** `.github/workflows/ci.yml` (`smoke` job), `e2e/package.json`

CI runs `npm run test:smoke` — `playwright test --project=workflow --grep @smoke`. The full suite is
`npm run test` (`--grep-invert @smoke`) and **nothing invokes it**. `.github/workflows/` contains
exactly one file; there is no nightly, no scheduled trigger, no pre-release workflow. The CI comment
says the full suite "belongs on a nightly or pre-release schedule" — that schedule does not exist.

Twelve spec files across `user-portal/`, `admin-portal/` and `workflow/` are therefore verified only
when someone runs them by hand, including `user-portal/negative.spec.ts`, which is where the
error-path and rate-limit assertions live.

**Why it is a finding:** the brief asks specifically for "browser tests that aren't included in CI".
This is the whole suite minus nine specs.

---

### BH-049 — Cross-browser and responsive projects never run

- **Severity:** Medium · **Test infrastructure**
- **File:** `e2e/package.json` (`test:browsers`, `test:responsive`)

Firefox, Edge, tablet and mobile-viewport projects are configured and never invoked by any workflow.
The CI comment justifies excluding them from the per-PR run ("a pre-release check") — the pre-release
run does not exist either.

---

### BH-050 — The rate-limit test passes vacuously when the limit is not reached

- **Severity:** Medium · **Test infrastructure**
- **File:** `e2e/tests/user-portal/negative.spec.ts:211`

```ts
test.skip(!limited, `no 429 within ${attempts} attempts — the test stack's ceiling is higher`);
```

A skipped test is a green test. Since CI raises `RATE_LIMIT_*_MAX` to 10000 for the stack it starts,
this assertion could never fire there even if the job did run it (BH-048). This is precisely the
"tests that can pass while the feature is broken" case the brief asks to be reported: if
`RateLimitFilter` stopped limiting entirely, this test would skip rather than fail.

---

### BH-051 — No test covers the cancel/complete race or recovery attempt accounting

- **Severity:** High · **Test infrastructure**
- **File:** `backend/src/test/java/com/finora/imports/jobs/` (8 classes)

`ImportJobEndpointIT` asserts that cancel sets `CANCELLED` and is idempotent. Nothing exercises a
cancel arriving **while a worker holds the job** — the case BH-001 reproduces — and nothing asserts
that a recovered job eventually dead-letters (BH-002). Both bugs are in the two-week-old code and both
are invisible to a suite of 1,055 tests.

The shape of the gap is instructive: the tests cover the endpoint's contract and the store's SQL, and
the state machine's *interaction* between them is where both defects live.

---

### BH-052 — No test asserts closing-balance corroboration for a liability account

- **Severity:** Medium · **Test infrastructure**
- **File:** `ClosingBalanceGuard` tests (searched; none exercise `CREDIT_CARD` semantics)

BH-004 is a one-line omission in a method with no account-type parameter. A single test case with a
card statement's arithmetic would have caught it. `AccountBalanceConvention` has the inversion tested;
the guard that must agree with it does not.

---

### BH-053 — The documented check-then-act race in merchant learning has no test

- **Severity:** Medium · **Test infrastructure**
- **File:** `service/MerchantLearningService.java:104-140`

Both the class comment and the method Javadoc describe the defect precisely and both explicitly warn
that `Propagation.REQUIRES_NEW` is *not* the fix (it would fail every first-time merchant on a foreign
key). The documentation is exemplary. What is missing is a test: nothing asserts the current
transaction behaviour, so a contributor who applies the tempting annotation anyway ships the
foreign-key regression with a green suite, and the only thing standing between them and that is two
comments they have to read first.

The wider point for this hunt: this is the codebase's most carefully documented known defect, and it
is also the one most exposed by the brief's rule "do not assume that because your tests pass, the
implementation is correct." A comment is not a guard.

---

### BH-054 — A push to a branch with no open PR gets no CI

- **Severity:** Low · **Test infrastructure**
- **File:** `.github/workflows/ci.yml` (`on: push: branches: [main]`)

Documented and deliberate ("The trade is real and worth stating"). Recorded because it interacts with
BH-048: neither the branch push nor the PR runs the full E2E suite, so the only automated browser
coverage any change ever receives is nine smoke specs.

---

## Performance Concerns

### BH-055 — `DuplicateDetector.tally` re-fetches the whole batch by id

- **Severity:** Low
- **File:** `imports/DuplicateDetector.java:107-115`

`transactionRepository.findAllById(savedBatch.stream().map(Transaction::getId).toList())` builds a
single `IN` clause with one entry per imported row — 5,000 UUIDs for a large statement. The re-fetch
is justified (reconciliation mutated the rows through its own repository), but the shape is a large
`IN` rather than a scoped `WHERE statement_import_id = ?`, which would return the same set.

### BH-056 — `clearReconciliationPointersTo` issues three large `IN` queries plus a save per row

- **Severity:** Low
- **File:** `transactions/TransactionService.java:475-496`

Three `findBy…In(removedIds)` queries with up to 500 ids, then one `save()` per affected row rather
than a batched `saveAll`. `ReconciliationService` explicitly moved to a single batched write for the
same reason ("each `save()` was its own round trip"); this method kept the per-row form.

### BH-057 — Bulk operations are N+1 by construction

- **Severity:** Low
- **File:** `transactions/TransactionService.java:447-458`, `:518-533`

`bulkDelete` and `bulkRecategorize` call `getOwned(userId, id)` per id — 500 `findById` calls in one
transaction, followed by 500 saves, followed by `reconcileForUser` + `detectForUser`. The list is
correctly bounded at `MAX_BULK_IDS = 500` (a good, documented fix), so this is a ceiling rather than an
unbounded hazard; a single `findAllByIdInAndUserId` would remove it.

---

## False Positives / Rejected Findings

Recorded so they are not re-investigated.

**RJ-01 — "Unbounded bulk delete."** I expected `bulkDelete`/`bulkRecategorize` to take an unbounded id
list. They are `@Size(max = MAX_BULK_IDS)` at `transactions/TransactionDto.java:88-115`, with a comment
documenting the ~270,000-UUID request that motivated the bound. **Not a bug** — the residual N+1 is
filed as BH-057.

**RJ-02 — "SQL injection in search."** Every search path binds parameters and the LIKE terms are
escaped via `util/LikePatterns`. `grep` found zero native queries built by concatenation and zero
`createQuery(... + ...)` call sites. The one native query in the codebase
(`ImportJobRepository.claimDueJobs`) is fully parameterised. **No injection surface found.**

**RJ-03 — "Path traversal via uploaded filename."** `StatementUpload.safeFileName` strips separators
and control characters; object keys are derived from the content hash, never the filename; and
`FilesystemStatementStorage.resolve` independently enforces root containment. Three independent
controls, each sufficient. **Not exploitable.**

**RJ-04 (was BH-040) — "Deleted migration under `baseline-on-migrate: true`."** V58, V69 and V70 are
absent from `db/migration/`. Flyway tolerates gaps, but a migration applied to some environment and
later deleted from the tree fails `validate` on that environment alone — and `application.yml`'s
comment asserts the numbers are "unused" without evidence. Checked against git history:

```
V58 ever committed:
V69 ever committed:
V70 ever committed:
--- confirm nothing was deleted ---
(no output: --diff-filter=D over db/migration/ across all refs returns nothing)
```

None of the three was ever committed, and **no migration file has ever been deleted on any branch**.
The comment's claim is correct. **Closed.**

---

## Areas Reviewed

Every area below was opened and read. Areas with no findings are listed explicitly.

| Area | Findings | Notes |
|---|---|---|
| **Async import / job queue / workers** | BH-001, 002, 008, 011, 018, 019, 020, 021, 022, 029, 051 | Densest cluster. Two Critical/High defects reproduced. |
| **Import / statement processing** | BH-006, 023, 024, 025, 028, 045 | Confirm path is where financial correctness concentrates. |
| **Balance calculations** | BH-003, 004, 005, 027 | All three reproduced. |
| **Reconciliation / duplicate detection** | BH-005, 007, 027, 041 | Refund pass is the weakest of the three passes. |
| **Recurring detection** | BH-026 | Two-point "pattern" is unconditional. |
| **Reports / dashboard** | BH-005, 042 | Correctness bug inherited from reconciliation. |
| **Authentication & authorization** | BH-012, 013, 014, 015, 016, 031, 038 | Authorization itself is sound — see below. |
| **Password / reset / session management** | BH-015, 016, 038 | Reset flow is well constructed; two-factor gate is real. |
| **File upload / storage** | BH-010, 017, 039, 046 | Storage layer is one of the strongest parts of the codebase. |
| **Rate limiting** | BH-011, 030, 034, 035 | One endpoint gap; the path-matching fix is genuinely good. |
| **API contracts** | BH-006, 008, 009, 010, 023, 029 | Three unvalidated inputs reaching the 500 catch-all. |
| **Merchant learning** | BH-021, 053 | The known race is documented; the two queues' recovery policies disagree. |
| **Categorization** | — | `CategorizationService`/`MerchantNormalizationEngine` read cleanly; the side-effect-rule override bug is already fixed on both write paths. |
| **Accounts & transactions** | BH-009, 027, 041, 056, 057 | `OwnershipGuard` consolidation is well done; `create()`'s historical IDOR is closed. |
| **Budgets / goals** | — | `BudgetService` is small and correct; the timezone and null-category-key bugs are already fixed, and the dead `catch` block was correctly removed with an explanation. |
| **Investments / net worth** | — | `NetWorthService` handles the same-day snapshot race correctly (its `catch` **is** reachable — no transaction, so `save()` flushes in-call, unlike `BudgetService`'s). |
| **Database migrations & schema** | BH-019, 040 | V66/V67 partial indexes are correctly reasoned. Three unused version numbers. |
| **Observability / Sentry** | BH-028, 044 | `send-default-pii: false`, logback appender disabled, scrubber in place. |
| **Metrics / monitoring** | — | `/actuator/prometheus` is authenticated (asserted by `WorkerMetricsExportIT`); `check-dashboard-metrics.py` closes the Micrometer-rename gap. No findings. |
| **CI/CD** | BH-048, 049, 050, 054 | Unusually strong guard-script layer; the gap is E2E scheduling. |
| **Docker / deployment** | BH-037, 043, 045 | Non-root, `MaxRAMPercentage`, graceful shutdown, `$PORT` — all correct. |
| **Frontend** | BH-012, 013 | Envelope unwrapping, 401 handling and `safeStorage` are all sound; the localStorage refresh token is the one real gap. |
| **Mobile** | — | `expo-secure-store` for credentials, the same shared-refresh guard, `EXPO_PUBLIC_API_BASE_URL` fails loudly when unset. No findings; mobile has **not** adopted the async import path. |
| **Admin portal** | — | Client mirrors the web one; `check-client-auth-policy.py` enforces that. Every `Admin*Controller` carries a class- or method-level `@PreAuthorize` (verified across all 27). No findings. |
| **Tests & test infrastructure** | BH-048…054 | Zero `@Disabled`/`@Ignore` in 1,055 backend tests; zero `.only(` in any JS suite. |
| **E2E / Playwright** | BH-048, 049, 050 | The harness is good; its scheduling is the problem. |
| **Configuration & environment** | BH-031, 032, 033, 036, 040 | `ProductionConfigValidator` is thorough and correctly placed in the lifecycle. |
| **Multi-tenant isolation** | — | `OwnershipGuard` is the single implementation, fails closed on every null, and is enforced by `OwnershipGuardUsageTest`. Every service-level fetch I traced goes through it. **No IDOR/BOLA found.** |
| **Injection / XSS / SSRF** | — | No SQL/JPQL injection (RJ-02). No `dangerouslySetInnerHTML`, `innerHTML`, `eval` or `new Function` in any of the three apps. R2 endpoint is validated as absolute HTTPS; no other outbound URL is user-influenced. |
| **Secrets in code/config** | — | No committed credentials found. Every secret is `${VAR:}` with an empty or obviously-placeholder default, and the placeholders are rejected in prod. |
| **Security headers / CORS** | BH-036 | CSP, HSTS, `frame-ancestors 'none'`, referrer policy and permissions policy all set explicitly. CORS origins are trimmed and explicit. |

---

## Areas Not Fully Reviewed

Stated precisely, with the reason.

1. **PDF extraction internals** — `imports/pdf/PdfTableLocator.java` (1,358 lines), `PdfPreviewGenerator`, `PdfMetadataExtractor`, `StatementSummaryExtractor`. I read their contracts and call sites but did not audit the column-anchoring and row-bucketing algorithms line by line. This is the single largest and most defect-prone body of code in the repository (its own comments record five separate silently-wrong-data bugs found against real statements), and it needs a dedicated pass with the statement corpus — which lives outside the tree by policy and was not available to me. **Highest-value area left unreviewed.**

2. **`imports/product/` (14 classes)** — financial product classification and identity resolution. `ProductIdentityResolver.resolve` decides whether a re-imported deposit is the *same* deposit, and `ImportService.resolveTargetAccount` acts on that decision by silently redirecting an import into an existing account. That is a financial-correctness-critical decision I read the call site of but did not audit the matching logic behind.

3. **`imports/analysis/` and `imports/trace/`** — the evidence and trace layers. Read enough to establish that BH-028's gap exists; the recording logic itself is unreviewed.

4. **Admin services (24 classes in `service/Admin*`)** — I verified authorization coverage on all 27 admin controllers but did not read the service bodies. Cross-tenant reads inside an admin service would not be caught by the controller-level check.

5. **`util/BankRegistry.java` (451 lines) and `CategoryRules`** — static data tables. Read their interfaces only.

6. **Runtime behaviour** — I ran no server, no database and no browser. Every reproduction is either a direct execution of the repository's compiled classes (BH-001, 002, 003, 004, 005, 008, 009) or a code trace. Nothing here was observed against live Postgres, live R2 or a real deploy.

7. **The statement corpus** — unavailable by policy. Everything about real-document parse accuracy is therefore out of scope for this pass, which is why item 1 above matters most.

8. **`e2e/` spec bodies** — I established what runs and what does not (BH-048/049/050) but did not review the twelve specs' assertions for vacuity individually beyond the one skip I found.

9. **Work in flight during this hunt.** Three files changed on disk *while this review was running*, by
   something other than this review (timestamps 23:21–23:32, after the tree had been read):
   `imports/pdf/PdfTableLocator.java` (+279 lines), `imports/CapabilityCoverageService.java` (+7/−2),
   and a new untracked `imports/pdf/WrappedHeaderProbe.java`. The two tracked edits had been reverted
   by the time this report was finalised; the untracked probe file remains.

   **Nothing in this report reviewed that work**, and every line number quoted for `PdfTableLocator`
   refers to the committed version at `661edce`. The reproductions are unaffected — they execute
   `ImportJob`, `ClosingBalanceGuard` and `AccountBalanceConvention` from `backend/target/classes`,
   none of which that work touches. Whoever is developing wrapped-header handling should treat the PDF
   pipeline (already item 1 above) as still un-reviewed.

---

## Appendix — Reproduction Harnesses

Both were written to a scratch directory outside the repository, compiled against
`backend/target/classes`, and executed. Neither is committed. Transcripts are inline with the findings
above.

**`CancelRace.java`** — BH-001 and BH-002. Drives `ImportJob` through the exact sequence
`ImportJobWorker.runOne` performs (`markClaimed` → `advanceTo(ANALYZING)` → concurrent `cancel` →
`complete` → the generic catch's `recordFailure`), then loops `markClaimed`/`returnToQueue` twelve
times to show the attempt counter never advances.

**`FinancialProofs.java`** — BH-003, BH-004 and BH-005. Calls `ClosingBalanceGuard.assess` with a real
credit-card statement's arithmetic; applies `AccountBalanceConvention.netDelta` twice to model a
re-import; and reproduces the dashboard's one-sided refund filter.

**`ParamProofs.java`** — BH-008 and BH-009. Calls `PageRequest.of(0, 0)` and
`Sort.Direction.fromString("bogus")` against the project's own `spring-data-commons` to confirm both
throw `IllegalArgumentException`, which `GlobalExceptionHandler` does not handle.

To re-run:

```bash
javac -cp backend/target/classes -d /tmp/hunt /tmp/hunt/CancelRace.java && java -cp /tmp/hunt:backend/target/classes CancelRace
```

---

## Remediation notes

Batch 1, branch `fix/bug-hunt-remediation-batch-1`. Written up here rather than only in commit
messages because two of the entries are decisions not to fix something, and those are the ones that
get lost.

### Fixed

| ID | What changed |
|---|---|
| BH-001 | `ImportJob.recordFailure` refuses to move a job that has already reached a terminal state and reports `FailureOutcome.ALREADY_FINISHED`; `ImportJobWorker` branches on that outcome instead of assuming a retry. The refusal in `complete()` was already there — what was missing was anything making it stick. |
| BH-002 | Recoveries are counted in their own column (`recovery_count`, V73) instead of sharing `attempt_count` with claims, so the increment and decrement can no longer cancel. `MAX_RECOVERIES = 3` bounds the loop and `ImportJobStore.recoverAbandoned` logs at ERROR when a job exhausts it. |
| BH-003 | After reconciliation, `ImportService.confirm` reverses the balance contribution of the rows it just inserted that were flagged `DUPLICATE`. Only on the `netDelta` branch — an authoritative closing balance is absolute and already idempotent. |
| BH-004 | `ClosingBalanceGuard.assess` takes the account type and gets the formula from `AccountBalanceConvention.expectedClosingBalance`, which now owns the liability inversion for corroboration as well as for deltas. The decision's `details` carries `balanceConvention` so a warning says which convention it read. |
| BH-005 | New `RefundNetting` owns the rule; **four** readers now share it (`DashboardService`, `ReportService`, `AnalyticsService`, `InsightsService`) instead of four hand-written copies of a one-sided filter. A refund is netted off the purchase rather than the purchase being dropped — the only treatment also correct for a partial refund. |
| BH-008 | `ImportJobService.recent` clamps through `PageBounds.safeSize`. |
| BH-009 | `TransactionService.search` uses `Sort.Direction.fromOptionalString(...).orElse(DESC)`. |
| BH-010 | `MaxUploadSizeExceededException` handler returns 413 naming the limit. |
| BH-011 | `/api/v1/import/jobs` added to `RateLimitFilter`, sharing `importStageLimiter`. |
| BH-020 | `@Version` on `ImportJob` (V73), so the worker and the cancel endpoint can no longer silently overwrite each other. |
| BH-021, BH-022 | Both comments corrected. They asserted guarantees the code did not have, and BH-022's was the reason BH-001 survived review. |

Also registered a general `IllegalArgumentException` handler (400 rather than 500) as a backstop for
the next unclamped parameter. It logs at **WARN with the stack trace** on purpose: the handler cannot
tell a bad query parameter from a genuine internal defect, and answering 400 for the second without
leaving evidence would turn a bug into a silent client error.

### Deliberately not fixed, with reasons

- **BH-015 (phone-number disclosure).** Masking was implemented, then **reverted**: all three clients
  pass this value straight to Firebase to *send* the code (`ResetPassword.tsx` calls
  `sendPhoneVerificationCode(res.phoneNumber, …)`), so returning a masked string breaks every
  password reset. Closing it properly means inverting the flow — the user types their number, the
  client sends the OTP to what they typed, and `resetPassword` rejects a mismatch, a check it
  **already performs**. That is a UI change across three clients plus a product decision, not a bug
  fix. What did change: the endpoint was outside every limiter and is now behind
  `resetPasswordLimiter`, and the reasoning is recorded at the call site so the next reader does not
  repeat the attempt.

- **BH-006 / BH-023 (confirm accepts unvalidated client rows).** BH-003 removes the damaging
  *consequence* — a double-confirmed re-import no longer corrupts the balance. The underlying issue is
  that the ledger is not derived from the server's own parse, and `confirmReimport` has no staged
  session to compare against. Re-parsing to validate cannot work for a password-protected PDF, whose
  stored bytes are still encrypted and whose password is deliberately never persisted. This needs a
  persisted re-import session — a design change.

### Discovered while fixing

- **`ImportService.confirm(UUID, MultipartFile, ConfirmRequest)` is not `@Transactional`**, and the
  overloads it delegates to are reached by self-invocation, so their annotations do not apply — that
  whole path runs with no transaction and can commit partially. **Not a production path**: the
  controller uses `confirmSession`/`confirmMultiSection`, both annotated and both entered through the
  proxy, and `StatementImportService.confirmReimport` crosses a bean boundary. It is reached only by
  tests today. Worth an annotation before anything else starts calling it. Found because a first
  attempt at BH-003 held one `Account` reference across two saves and hit
  `ObjectOptimisticLockingFailureException` — exactly the `merge()`-returns-a-new-instance trap
  `BaseEntity`'s own class comment documents.

### Verification

- Full backend suite green: **1774 tests, 0 failures, 0 errors** (up from 1773 — the net is the new
  cases minus none removed; three failing tests during the batch were caught and fixed, not skipped).
- New regression tests: `ImportJobTest` (+3, including the cancel-race sequence that reproduces
  BH-001 end to end), `ClosingBalanceGuardTest` (+5 liability cases, closing BH-052),
  `RefundNettingTest` (+7), `ImportAccountBalanceIT` (+3 against real Postgres),
  `RateLimitFilterTest` (+1 table-driven test asserting every endpoint that must be limited — the
  shape that catches the *next* omission, not just this one).
- One existing test asserted the buggy behaviour and was **changed on purpose**:
  `ImportJobTest.recoveryReturnsAJobWithoutSpendingAnAttempt` codified the attempt-count decrement
  that made BH-002 unbounded. It still asserts the decrement (the intent is sound) and is now joined
  by `aJobThatKeepsKillingItsWorkerEventuallyDeadLetters`, which asserts the ceiling.
- `ImportJobEndpointIT` needed `app.rate-limit.import-stage.max` raised, for the same reason CI raises
  it for the e2e stack — it uploads past the ceiling from one loopback IP. The limiter is not switched
  off; that the endpoint is *in* the table is asserted by the new `RateLimitFilterTest` case.
- All CI guard scripts pass: `check-imports` (615 files), `check-xml-comments`,
  `check-client-auth-policy`, `check-fixture-hygiene` (tree ratchet at baseline 112),
  `check-dashboard-metrics`.

### Clean-room verification (2026-08-09, after a concurrent-run scare)

A `MerchantLearningImportIT` failure was reported that my runs did not show. The evidence for both
sides was invalid: two Maven processes were running against the same `backend/target/`, which shares
`classes`, `test-classes` and `surefire-reports` between them. Proof it happened rather than a guess:
`ExtractionParityDump`'s surefire report is timestamped `01:25:30`, inside my suite's
`01:24:13 -> 01:25:35` window, and my suite never ran that class.

Re-verified sequentially with build outputs removed first and no other Maven process running:

| # | Command | Result | Exit |
|---|---|---|---|
| 3 | `./mvnw -B test -Dtest=MerchantLearningImportIT` | 5 tests, 0F 0E | 0 |
| 4 | `./mvnw -B test -Dtest=ExtractionParityDump -Dfinora.parity.dir=<tmp> -Dfinora.parity.mode=generate` | 1 test, 0F 0E, 20 fixtures dumped | 0 |
| 5 | `./mvnw -B test` (whole suite) | **1777 tests, 0F 0E** | 0 |
| 6 | `./mvnw -B test` (whole suite, after the fixture-isolation fix below) | **1777 tests, 0F 0E** | 0 |
| — | `MerchantLearningImportIT` × 5 consecutive isolated runs | 5/5 pass | 0 |

`ExtractionParityDump` is not a broken test and should not be carried as one. It is the temporary
PDFBox 3.0.3 -> 3.0.8 measurement harness, excluded from the suite by a class name that deliberately
does not match surefire's includes. Run bare it throws NPE at
`Path.of(System.getProperty("finora.parity.dir"))` -- a required property, not a defect. Given its two
properties it passes and writes its dump.

**Caveat worth keeping.** Clean runs cannot prove the absence of a rare intermittent failure. What
they establish is that the reported failure does not reproduce under controlled conditions, and that
there was a concrete mechanism capable of manufacturing it. If it recurs on a run with nothing else
touching `target/`, the assertion output is the thing to capture.

**Operational note.** Two Maven invocations in one working directory will keep producing phantom
failures. Separate checkouts, or `-Dmaven.build.dir`, if backend work is ever going to overlap.

### Fixture isolation (follow-up, done)

`ImportAccountBalanceIT` now removes the `merchant_learning_events` rows its own users generate, in
an `@AfterEach`. Every confirmed import enqueues learning events, the test profile disables the
worker, so nothing drained them and they accumulated in a table every integration test in the JVM
shares. `MerchantLearningImportIT.drainUntilSettled` documents that exact coupling -- `drainOnce()`
claims a bounded batch of 50 across the whole table -- and defends itself by backdating its own
events so they sort first. That defence looks sound and was not weakened; what changed is that this
class stops adding to the pile the next test author may not defend against. Scoped to its own users
rather than truncating the table, so the cleanup is not itself cross-test coupling.

---

## Batch 2 (2026-08-09)

| ID | What changed |
|---|---|
| BH-013 | Refresh is serialised across tabs with `navigator.locks`, and — the half that actually matters — the loser **re-checks** the stored access token inside the lock and skips the refresh when another tab has already done it. Waiting and then refreshing anyway would only serialise the two calls and still present a rotated token. Falls back to running directly where Web Locks is unavailable. |
| BH-012 | The refresh token is no longer persisted anywhere on web. `authApi.refresh()`/`logout()` send no body; the HttpOnly cookie is the whole transport. No backend change was needed for those two — `@RequestBody(required = false)` and `RefreshTokenCookie.resolve` already preferred the cookie. |
| BH-026 | `MIN_OCCURRENCES_FOR_A_PATTERN = 3`. At 2 the regularity check could not fail: one gap means `avgGap` IS that gap, so `gapRegular` was unconditionally true and any two similar charges 5–95 days apart became a "Monthly" subscription with a predicted next date. |
| BH-042 (part) | `ReportService.availableMonths` loaded the entire ledger to build a dropdown; it reads distinct dates from the database now. The other seven full-history loads are entangled with BH-041 and are untouched. |

### The knock-on nobody would have predicted from the finding

BH-012 could not be done in the client alone. `ChangePasswordModal` read the refresh token from
`localStorage` and posted it as `CompleteRequest.currentRefreshToken`, so the backend could spare
"this device" from *sign out my other devices*. Take the token away and that breaks — and the cookie
cannot substitute, because it is path-scoped to `/api/v1/auth` and never reaches
`/api/v1/users/me/password-change/complete`.

`revokeAllOtherSessionsForUser` is now keyed on the **session id** from the access token's `sid`
claim instead of a raw token hash. That is better independently of BH-012, and it fixes a live bug
the old form had: ADR-002 makes the session the unit precisely because a token rotates roughly every
fifteen minutes, so a token that rotated between the client reading it and the server checking it
matched nothing — and "this device" was revoked along with all the others. `currentRefreshToken` is
now optional and ignored, kept on the record only for the mobile support window (relaxing a required
request field is non-breaking under the API compatibility policy; removing it would not be).

### Also worth knowing: a compile error can reach runtime here

Adding that controller change without `import java.util.UUID` produced **`BUILD SUCCESS` from
`mvn test-compile`** and a `java.lang.Error: Unresolved compilation problems` thrown from the
handler at request time — surfacing as a 500 in `PasswordChangeFlowIT`, four tests away from the
cause. The Eclipse compiler emits a class containing the error rather than failing the build.

The practical consequence for anyone working here: **grepping a compile for the word ERROR is not a
compile check.** Read the `BUILD` line. A green `test-compile` does not mean the code compiles.

### Verification

- Backend: **1783 tests, 0 failures, 0 errors, BUILD SUCCESS**, sequential, no concurrent Maven.
- Frontend: **321 tests, 34 files, all passing**; `tsc -b` clean; `eslint --max-warnings 0` clean;
  production build succeeds.
- Mobile `tsc --noEmit` clean — it still sends a body token and is deliberately unaffected.
- All CI guards pass, including `check-client-auth-policy` (the three clients still agree).
- Four tests changed to assert the new contract rather than the old: the two `AuthContext` storage
  assertions now assert the refresh token is **absent** (and absent under *any* key, so stashing it
  elsewhere cannot pass), and the two password-change tests verify revocation is keyed on the
  session. One test — `logout() is a safe no-op when there is no session` — caught a real mistake:
  the first cut called `authApi.logout()` unconditionally. Gated on the access token instead.
