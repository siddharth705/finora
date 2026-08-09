# Repo-Wide Bug Hunt — Round 1 Closure Report

**Status:** formal baseline closing **Bug Hunt Round 1**. Not a declaration that Finora is bug-free.
**Branch:** `fix/bug-hunt-remediation` (pushed, tracking `origin`)
**Base:** `main` @ `64ea4e9`
**Hunt report:** [`2026-08-08-repo-wide-bug-hunt.md`](2026-08-08-repo-wide-bug-hunt.md)

> **This repository is not defect-free, and a green test suite is not the claim being made here.**
> 1810 backend tests pass. That says the changes on this branch did not break what the suite covers.
> It does not say the repository is correct, and several of the largest subsystems were never
> reviewed at all — see [What this hunt did not cover](#what-this-hunt-did-not-cover), which is the
> most important section of this document.

---

## 1. Inventory at a glance

61 distinct items were investigated: BH-001 … BH-058, plus three rejected candidates (RJ-01 … RJ-03)
that never earned a BH number.

| Disposition | Count | Meaning |
|---|---:|---|
| **CLOSED — VERIFIED** | 9 | The broken behaviour was independently demonstrated, then shown to be gone |
| **CLOSED — REVIEWED** | 22 | Root cause established and fixed, suite green, but the break was never demonstrated |
| **OPEN** | 14 | Confirmed issue, remediation outstanding |
| **DEFERRED — DECISION REQUIRED** | 2 | Behaviour or product semantics need an explicit decision |
| **DEPLOYMENT VERIFICATION REQUIRED** | 1 | Cannot be closed from the repository alone |
| **NEEDS REPRODUCTION** | 2 | Confirmed by inspection; deliberately not closed without a repro |
| **CANNOT REPRODUCE** | 1 | Conditions attempted and recorded |
| **ACCEPTED** | 6 | Real, understood, deliberately not being changed |
| **REJECTED** | 4 | Investigated, not a defect |
| **Total** | **61** | |

### The two closure grades, and why they are not one grade

"Closed" is not a single claim, so this report does not make it one.

- **CLOSED — VERIFIED** requires that the defect was *demonstrated* — the broken behaviour observed
  directly (by executing the repository's own compiled classes, or by a test that failed before the
  fix), and then observed to be gone. Five were proven the first way, four by mutation testing the
  regression test after the fix. Nine items clear this bar.
- **CLOSED — REVIEWED** means the root cause was established, the fix is understood, a regression
  test exists in most cases, and the full suite is green — but nobody ever watched the bug happen.
  Twenty-two items sit here. Each row in §2.2 states what specifically is missing.

A REVIEWED closure is not a lesser fix; it is a lesser *proof*. The failure mode it permits is
closing something that was never actually broken in the way the finding described, or fixing a
different bug that happens to sit nearby. **Do not upgrade a REVIEWED item to VERIFIED because the
suite is green** — the suite being green is already part of what REVIEWED means. Upgrading requires
demonstrating the break.

---

## 2. CLOSED — with evidence

Every row answers: original behaviour → reproducible? → root cause → what changed → regression test
→ **demonstrated to fail against the break?** → verified → unverified.

### 2.1 CLOSED — VERIFIED (9)

Demonstrated against the broken behaviour.

| ID | Original behaviour | Root cause | Demonstration |
|---|---|---|---|
| **BH-001** | Cancelling an in-flight import silently un-cancelled it; the job re-ran and staged the session the user had stopped | `recordFailure` wrote `status = QUEUED` unconditionally; the worker catches `ImportJobCancelledException`, but `complete()` throws `IllegalStateException`, so the general handler resurrected the job | **Executed against `target/classes` pre-fix**: `after cancel: CANCELLED` → `complete() threw IllegalStateException` → `recordFailure` → `status=QUEUED`. Transcript in the hunt report |
| **BH-002** | A job whose parse killed its worker never dead-lettered | `markClaimed()`++ and `returnToQueue()`−− exactly cancelled | **Executed pre-fix**: 12 crash/recover cycles → `attempts=0`, `MAX_ATTEMPTS=5` |
| **BH-003** | Re-importing a statement moved `Account.balance` twice; duplicates were hidden from reports but not from the balance | `confirm()` applies `netDelta` before reconciliation flags duplicates; nothing reversed it | **Executed pre-fix**: card balance `4000.00` → `3000.00` on the second import |
| **BH-004** | Every arithmetically correct credit-card statement reported UNCORROBORATED and never had its closing balance applied | `ClosingBalanceGuard` used the asset formula for all account types | **Executed pre-fix**: expected `6000.00` vs the card's real `4000.00`, off by exactly 2×net |
| **BH-005** | A fully refunded purchase reported as a pure loss | Reconciliation marks only the INCOME leg `REFUND`; four readers dropped it and kept the expense | **Executed pre-fix**: dashboard net `−500.00` for a month where `0.00` moved |
| **BH-047** | Expired-session sweep ran inside the acting user's upload transaction, holding locks on other users' rows across an object-storage write | Housekeeping placed in the caller's transaction | **Reproduced pre-fix** (both cases failed), then **mutation-checked**: restoring the call fails the test on the right assertion |
| **BH-028** | A parser crash produced a 500 and **no** evidence row, permanently | Both catches were `ApiException` only | **Reproduced pre-fix** (`Expected size: 1 but was: 0`), then **mutation-checked**: narrowing the catch fails the crash test and leaves both controls green |
| **BH-058** | A concurrency test passed only because the rest of the suite happened to leave the queue empty | `claimDueEvents` is table-wide; the assertion was not scoped | **Mutation-checked**: removing `SKIP LOCKED` fails both tests on the right assertions |
| **BH-041** | A multi-section import ran the whole reconciliation pipeline once per section, at user-wide scope; and a transfer between two sections was counted on one side only | `confirmMultiSection` looped `confirm()`, which reconciled in its own tail — so section 1 was summarised before section 2 existed | **Mutation-checked**: restoring the interleaved persist/reconcile/summarise shape fails the new test on `expected: 1 but was: 0` for "each section sees the transfer its own row is half of". A weaker first mutation, which split only the reconcile, did **not** reproduce it — recorded because the test was strengthened until it could tell the real old behaviour apart |

### 2.2 CLOSED — REVIEWED (22)

Most have a regression test, and each test fails for a specific nameable change — but no mutation
was run to prove it. Listed separately rather than folded in with the nine above. Two of them
(BH-010, BH-016) have no test at all and are the weakest closures on the branch.

| ID | What changed | Regression test | Not demonstrated because |
|---|---|---|---|
| BH-008 | `recent()` clamps via `PageBounds` | — | Behaviour proven by executing `PageRequest.of(0,0)`; no dedicated test added |
| BH-009 | `sortDir` via `fromOptionalString` | — | Same; proven by execution, no dedicated test |
| BH-010 | 413 handler for oversized uploads | — | **No test added.** Handler registration is unverified by the suite |
| BH-011 | `/import/jobs` + `/auth/reset-password/phone` rate-limited | `RateLimitFilterTest.everyEndpointWithARealPerCallCostIsLimited` (table-driven) | Table shape makes omission the failure mode; mutation not run |
| BH-012 | Refresh token no longer persisted | `AuthContext.test` asserts absent under *any* key | Client-side; no mutation harness |
| BH-013 | Cross-tab lock + re-check | `client.test.ts` — lock stubbed, re-check asserted | jsdom has no Web Locks; the lock itself is stubbed, so only the re-check logic is covered |
| BH-016 | Email after commit + HTTP timeouts | — | **No test added.** `AfterCommit` behaviour and the timeouts are unverified by the suite |
| BH-019 | Live-job dedup + partial unique index (V74) | 3 cases in `ImportJobEndpointIT` against real Postgres | Index enforcement under true concurrency not tested |
| BH-020 | `@Version` on `import_jobs` (V73) | — | No concurrent-write test; the column's presence is verified only by the schema validating |
| BH-021, BH-022 | Two comments corrected | — | Documentation |
| BH-024 | One aggregate query replaces a full-table load | Existing `ImportAccountBalanceIT` cases | Equivalence is argued, not proven by a differential test |
| BH-026 | `MIN_OCCURRENCES_FOR_A_PATTERN = 3` | 3 cases in `RecurringServiceTest`, incl. two negatives | Mutation not run; the two-charge case would catch a revert |
| BH-027 | `POST /transactions/{id}/not-duplicate` | 4 cases in `NotDuplicateConfirmationIT` incl. balance and ownership | New capability — no prior behaviour to mutate back to |
| BH-030 | Time-based sweep + `trackedKeys()` seam | 2 cases in `RateLimiterTest` | Mutation not run |
| BH-042 *(part)* | `availableMonths` uses a DISTINCT query | `ReportServiceTest` ×2, `TransactionRepositoryIT` ×1 | **Partial** — 7 other full-history loads remain OPEN |
| BH-048, BH-049 | `e2e-nightly.yml` | YAML validated; job never executed | **The workflow has never run.** Verified as syntax, not as a working pipeline |
| BH-051, BH-052 | Closed by batch 1's tests | `ImportJobTest` ×3, `ClosingBalanceGuardTest` ×5 | These *are* the missing tests the findings named |
| BH-055, BH-056, BH-057 | Query/write shapes | `BulkDeleteBehaviourIT` ×5 incl. 3 negatives | Behaviour-level; no mutation run |

---

## 3. OPEN — confirmed, remediation outstanding

Each re-verified as still reproducing at `5a4c985`.

| ID | Class | Impact |
|---|---|---|
| BH-014 | Security | Lockout returns 423 vs 401 → five wrong passwords turn any email into an existence oracle |
| BH-018 | Design | `accept()`'s documented ordering does not match its code; whole file held in memory |
| BH-025 | Performance | Multi-section confirm writes a full copy of the document per section into Postgres |
| BH-029 | Design | Parser format decided by filename; should be persisted on the job row |
| BH-032 | Security (minor) | DB-password check matches only the literal `"finora"` |
| BH-036 | Confirmed (latent) | CORS forbids `X-Request-Id`, the header `CorrelationIdFilter` advertises |
| BH-037 | Security (dev) | `docker-compose` publishes Postgres on `0.0.0.0:5432` with `finora/finora` |
| BH-042 *(remainder)* | Performance | 7 services still load the user's entire transaction history |
| BH-043 | Design | `ImportConcurrencyLimiter` blocks Tomcat request threads up to 20s |
| BH-044 | Performance | A `RECONCILIATION_RUN` audit row per write; `audit_logs` unbounded, no retention |
| BH-045 | Performance | Whole files held in memory at four layers |
| BH-046 | Design | Dual write to `file_content` has no end trigger |
| BH-050 | Test defect | `negative.spec.ts` self-skips — would pass if rate limiting were removed entirely |
| BH-053 | Test gap | `MerchantLearningService.confirm`'s documented race has no test |
| *(new)* | Test defect | Other tests may share BH-058's table-wide assumption. **Not swept.** Only the one demonstrated to break was fixed |

### BH-041 in full — DEFERRED → OPEN → CLOSED–VERIFIED, all on 2026-08-09

Kept end to end because the route matters more than the destination: it was deferred for a reason
that turned out to be false, and the only thing that found that was reading the code.

**Why the deferral was wrong.** It was held as a product decision on the belief that reconciling
once would strip the per-section `duplicatesDetected` / `transfersIdentified` of any meaning. Each
section in fact creates its own `StatementImport`, and `DuplicateDetector.tally()` is a **post-hoc
read of persisted flags scoped by that `statementImportId`** — indifferent to how many passes ran
or when. Persist-all → reconcile-once → summarise-each leaves every count exactly as it was. **No
API change, no product decision.**

**What shipped.** `confirm()` split into `persistSection()` and `summarise()`;
`confirmMultiSection` persists all sections, reconciles once, then summarises each. The import path
reconciles through a new `reconcileForImport(userId, min, max)` over a symmetric ±180-day candidate
window; `reconcileForUser` is untouched for its seven other callers, one of which depends on the
unbounded re-scan. The window is **derived from the widest matching window** rather than typed as
`180`, so it follows `REFUND_WINDOW_DAYS` if that ever moves.

**The live defect it also fixed.** Section 1 was summarised before section 2's rows existed, so a
transfer between two sections of one statement was counted on one side only —
`transfersIdentified` 1 for the later section, 0 for the earlier.

**Scope expansion, acknowledged.** `recurringService.detectForUser` was hoisted alongside
reconciliation. That went past the approved wording. It is safe for a structural reason, not a
circumstantial one: `detectForUser` resets `setRecurring(false)` across every active transaction and
re-derives every pattern from scratch, so it is a full recomputation — idempotent, with only the
last run's output surviving. Its ordering dependency holds too (it filters out transfers and
duplicates, so it must follow reconciliation, and it does). One `RECURRING_DETECTION_RUN` audit row
per import now, instead of one per section.

**Regression coverage.** `MultiSectionSharedTransferIT` (3 cases): a Savings→Card transfer split
across sections, re-imported — asserting cross-account detection, both legs classified, both
sections reporting it, balances unmoved by the re-import (BH-003), and one pass not three; a leg
arriving in a *separate earlier* import still matching (the case account-scoping would have broken);
and a recurring pattern split across sections. Plus `MultiSectionReconciliationCostIT` (3 cases)
for pass counts and the candidate window.

**Measured, on the same path with only the reconciliation shape swapped** — a 3-section import over
200 rows of history:

| | passes | statements | queries | elapsed |
|---|---:|---:|---:|---:|
| per-section + unbounded | 3 + 3 | 994 | 628 | ~514–629 ms |
| once + windowed | 1 + 1 | 938 | 616 | ~219–402 ms |

**A correction to this report's own earlier number.** The "+309 statements / +136 queries / +132 ms"
recorded when BH-041 was deferred compared three `confirm()` calls against one, so most of it was
two extra account resolves, `StatementImport` rows and transaction batches — per-section work that
is real and unchanged. The reconciliation repetition itself was worth ~56 statements and ~12
queries, plus a third to a half of wall-clock. A pass is only a couple of queries; its cost is the
in-memory O(n²) matching, which is why time moved far more than statement count. **The old headline
should not be quoted as the expected improvement.**

Windowing showed *no* benefit in that fixture — 994 either way — because every row sits inside
±180 days. A separate measurement gives three years of history and imports one month: **92
transactions on file, 20 candidates loaded, 78% left unread.**

---

## 4. DEFERRED — decision required

| ID | Decision needed | Evidence gathered |
|---|---|---|
| **BH-017** | Retention policy: reference-counted sweep vs R2 lifecycle rule | Statement bytes are never deleted from object storage by any path. BH-047 removes the database row; **it does not remove the object** |
| **BH-025** *(overlaps OPEN)* | Whether the BYTEA dual write should be skipped when an object address exists | Would change the migration's rollback story |

## 5. DEPLOYMENT VERIFICATION REQUIRED

| ID | What must be checked | Why it cannot be closed here |
|---|---|---|
| **BH-031** | Is `SPRING_PROFILES_ACTIVE=prod` actually set on Railway? | `ProductionConfigValidator` returns immediately unless the `prod` profile is active, and the profile defaults to `dev`. If unset: placeholder `JWT_SECRET` accepted, `forgotPassword` returns live reset links in the API response, Swagger served anonymously, 500s echo `ex.getMessage()`. **One environment-variable check closes or confirms this.** I have no access to the deployment |

## 6. NEEDS REPRODUCTION — not closed on inspection

| ID | Status |
|---|---|
| **BH-006 / BH-023** | Confirmed by inspection: `confirmReimport` accepts arbitrary client rows; `confirmSession` compares only the row *count*. Not closed without a persisted re-import session to reproduce against. BH-003 removed the damaging consequence (balance corruption); the underlying "ledger not derived from the server's parse" remains |

## 7. CANNOT REPRODUCE — conditions recorded

| ID | Attempted |
|---|---|
| **BH-033** | 2,000,000 trials, `SecureRandom.getInstanceStrong()`, 48 random bytes, run against the real production method. base64url: **1** rejection (~5×10⁻⁷, contained `dumMyy`). Hex: **0**, and structurally impossible — every marker has a character outside `[0-9a-f]`. Positive control confirms the matcher works. Deployment guide now specifies `openssl rand -hex 32`. **Would reopen if** a marker expressible in hex is added, or the check is applied to a value whose alphabet the operator does not choose |

## 8. ACCEPTED — real, understood, not being changed

BH-034 (single-instance controls vs a multi-instance roadmap), BH-035 (`X-Forwarded-For` last-hop
assumes one proxy), BH-038 (reuse detection has no grace window for a lost-response retry),
BH-039 (content addressing is global across tenants — matters only once a sweep exists),
BH-054 (a push with no open PR gets no CI — documented trade), BH-033 (recommend accept).

## 9. REJECTED — investigated, not defects

RJ-01 bulk id lists are bounded at 500 · RJ-02 no SQL injection surface (zero concatenated queries;
the one native query is fully parameterised) · RJ-03 path traversal blocked by three independent
controls · RJ-04 (was BH-040) V58/V69/V70 were never committed and no migration has ever been
deleted — verified against git history across all refs.

---

## 10. Final verification results

Run against a **detached worktree at `5a4c985`** — committed branch content only, with the parallel
workstream's files provably absent. **No concurrent Maven during the backend run.**

| Suite | Result |
|---|---|
| Backend | **1810 tests, 0 failures, 0 errors** — BUILD SUCCESS (238 classes, 65 integration) |
| Frontend | **321 tests**, `tsc -b` clean, `eslint --max-warnings 0` clean |
| Admin portal | **301 tests** (41 files) |
| Mobile | `tsc --noEmit` clean |
| CI guards | All 12 pass, including the four self-tests that prove the guards themselves detect a real break |

**What these numbers do and do not mean.** They mean the branch does not regress what the suite
covers. They do not mean the fixes are correct — for that, see which of the 30 closures were
demonstrated against the broken behaviour (8) and which were not (22).

---

## What this hunt did not cover

The most important section. Read it before treating any part of the repository as verified.

### Never reviewed

- **PDF extraction internals** — `PdfTableLocator` (1,358 lines), `PdfPreviewGenerator`,
  `PdfMetadataExtractor`, `StatementSummaryExtractor`. Contracts and call sites only; the
  column-anchoring and row-bucketing algorithms were never read line by line. **This is the largest
  and most defect-prone body of code in the repository** — its own comments record five separate
  silently-wrong-data bugs found against real statements. It needs the statement corpus, which lives
  outside the tree by policy.
- **`imports/product/` (14 classes)** — financial product classification and identity resolution.
  `ProductIdentityResolver.resolve` decides whether a re-imported deposit is the *same* deposit, and
  `ImportService.resolveTargetAccount` acts on that by silently redirecting an import into an
  existing account. Financial-correctness-critical; call site read, matching logic not audited.
- **Admin service bodies (24 classes)** — authorization coverage verified on all 27 admin
  controllers; the service implementations were not read. A cross-tenant read *inside* an admin
  service would not be caught by a controller-level check.
- **`imports/analysis/` and `imports/trace/`** beyond what BH-028 required.
- **`util/BankRegistry`** (451 lines) and `CategoryRules` — static data tables, interfaces only.

### Verified only as syntax or configuration

- **`e2e-nightly.yml` has never executed.** Valid YAML, correct labels, faithful to the smoke job's
  stack setup — but the pipeline has not run once. Its first real run will find whatever it finds.
- **V73 and V74 have never been applied to a non-test database.** Testcontainers proves they run
  against an empty schema; neither has met production data. V74 in particular contains a data
  mutation (cancelling duplicate live jobs) whose no-op assumption rests on the async queue being
  off by default.

### Not exercised at all

- **No runtime observation.** No server, no browser, no real deploy. Every finding is either a
  compiled-class execution, an integration test, or a code trace.
- **No load or concurrency testing** beyond individual `SKIP LOCKED` cases. BH-043 (thread-pool
  exhaustion) and BH-045 (memory) are reasoned, not measured.
- **No multi-instance testing.** Three controls are documented single-instance (BH-034); nothing
  verifies behaviour with two.
- **The full E2E suite has still never run in CI.** BH-048 added the schedule; the twelve spec files
  outside the smoke set remain unexercised by automation as of this report.

### Known weaknesses in the evidence itself

- **22 of 30 closures were not demonstrated against the broken behaviour.** Section 2.2 says which.
- **BH-010 and BH-016 have no test at all.** Both are code changes verified only by the suite not
  breaking — which is precisely the "green therefore fixed" reasoning this report rejects. They are
  listed as CLOSED because the change is small and the mechanism well understood, and that is a
  weaker basis than the other 28.
- **The BH-058 class of defect was not swept.** One table-wide test assumption was found by
  accidentally breaking it. Others are likely; nobody has looked.

---

---

## Round 2 — scope, in priority order

Round 2 is **not** "work through the 14 open items by number." Ticket order encodes when a defect
was found, not what it costs. The ordering below is by financial/data-integrity impact and
architectural blast radius.

1. **Financial correctness and import classification.**
2. **PDF extraction against the real-document corpus** — `PdfTableLocator` et al. This needs the
   corpus, and it is the single largest unreviewed risk in the repository.
3. **Import / re-import correctness** — including BH-006/BH-023, which are held for a reproduction.
4. **Async import execution and recovery.**
5. **Concurrency.**
6. **Security and authentication** — BH-014 is the notable open item (a 423-vs-401 existence oracle).
7. **Remaining operational defects.**
8. **Test-quality sweep** — starting with the BH-058 class: tests that pass only because the rest of
   the suite happened to leave shared state empty. One was found by accident; nobody has looked for
   the others.

Two of these deserve their own evidence-driven review rather than being folded into a general pass,
because both can silently corrupt financial data rather than fail loudly:

- **`PdfTableLocator`** — 1,358 lines, and its own comments record five separate
  silently-wrong-data defects already found against real statements.
- **`imports/product/`** — decides whether a re-imported deposit is the *same* deposit, and
  `ImportService.resolveTargetAccount` acts on that answer by redirecting an import into an existing
  account without telling anyone.

Also queued, and blocking: the two decisions in §4 and the environment check in §5. Those are not
engineering guesses.

---

## Merge recommendation

The branch is safe to merge on the evidence available: it closes 31 findings, adds 45+ regression
tests, breaks nothing the suite covers, and touches no file belonging to the parallel workstream.

**This branch closes the remediation cycle. It does not close the bug hunt.** The defensible
statement — the one to use in the PR, in status updates, and anywhere a number is quoted — is:

> 61 findings were investigated. 31 are classified closed (9 verified against the broken behaviour,
> 22 reviewed but not demonstrated), 14 remain confirmed and unfixed, and the rest are explicitly
> classified by evidence level. The branch passes its committed verification suite, but this report
> identifies significant areas that were not validated at all, including PDF extraction internals,
> import/product classification, production runtime behaviour, and the nightly E2E workflow.

What is *not* defensible is "1810 tests pass, therefore the system is validated." The 1810 are
worth having. The reason this document exists is to draw a written boundary around what they prove,
so that Round 2 does not begin from a false assumption of completeness.
