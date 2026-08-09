# Repo-Wide Bug Hunt — Closure Report

**Branch:** `fix/bug-hunt-remediation` @ `5a4c985` (pushed, tracking `origin`)
**Base:** `main` @ `64ea4e9`
**Hunt report:** [`2026-08-08-repo-wide-bug-hunt.md`](2026-08-08-repo-wide-bug-hunt.md)

> **This repository is not defect-free, and a green test suite is not the claim being made here.**
> 1806 backend tests pass. That says the changes on this branch did not break what the suite covers.
> It does not say the repository is correct, and several of the largest subsystems were never
> reviewed at all — see [What this hunt did not cover](#what-this-hunt-did-not-cover), which is the
> most important section of this document.

---

## 1. Inventory at a glance

61 distinct items were investigated: BH-001 … BH-058, plus three rejected candidates (RJ-01 … RJ-03)
that never earned a BH number.

| Disposition | Count | Meaning |
|---|---:|---|
| **CLOSED** | 30 | Reproduced, root cause established, fixed, regression test added, verified |
| **OPEN** | 14 | Confirmed issue, remediation outstanding |
| **DEFERRED — DECISION REQUIRED** | 3 | Behaviour or product semantics need an explicit decision |
| **DEPLOYMENT VERIFICATION REQUIRED** | 1 | Cannot be closed from the repository alone |
| **NEEDS REPRODUCTION** | 2 | Confirmed by inspection; deliberately not closed without a repro |
| **CANNOT REPRODUCE** | 1 | Conditions attempted and recorded |
| **ACCEPTED** | 6 | Real, understood, deliberately not being changed |
| **REJECTED** | 4 | Investigated, not a defect |
| **Total** | **61** | |

Of the 30 CLOSED, **8 were demonstrated to fail against the broken behaviour** — five by executing
the repository's own compiled classes before any fix, three by mutation testing after. The other 22
rest on weaker evidence, and each one says which below. That distinction is the point of this
report.

---

## 2. CLOSED — with evidence

Every row answers: original behaviour → reproducible? → root cause → what changed → regression test
→ **demonstrated to fail against the break?** → verified → unverified.

### 2.1 Demonstrated against the broken behaviour (strongest evidence)

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

### 2.2 Closed on a named regression, not demonstrated against the break

Each has a regression test, and each test fails for a specific nameable change — but I did not run a
mutation to prove it. Listed honestly rather than folded in with the eight above.

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

---

## 4. DEFERRED — decision required

| ID | Decision needed | Evidence gathered |
|---|---|---|
| **BH-041** | Intended semantics of `duplicatesDetected` / `transfersIdentified` for multi-section imports, before any optimisation | Measured: 3 sections cost **3 reconcile + 3 recurring passes vs 1+1**, **+309 statements, +136 queries, +132 ms** over a 200-row history. Per-section counts are structurally 0 on a first composite import and **5** on a re-import. **No consumer reads them on the multi-section path** — web's `MultiImportSummaryScreen` never renders them, mobile refuses multi-account statements outright, no e2e spec asserts them |
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
| Backend | **1806 tests, 0 failures, 0 errors** — BUILD SUCCESS (236 classes, 64 integration) |
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

## Merge recommendation

The branch is safe to merge on the evidence available: it fixes 30 findings, adds 40+ regression
tests, breaks nothing the suite covers, and touches no file belonging to the parallel workstream.

Merging it does **not** close the bug hunt. Fourteen findings remain open, one needs an environment
variable checked, three need product decisions, and the two largest subsystems in the repository
were never reviewed. The honest summary is that this branch materially improved a system whose
overall defect level is still unknown.
