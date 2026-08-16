# Finora — Project Plan to v1.0 GA

**Baselined:** 2026-08-09 · **Re-baselined:** 2026-08-11 (production-readiness audit + remediation pass)
**Owner:** Siddharth Tiwari · **Maintained by:** the PM role
**Status:** On Track (see §7)

> **Process note, recorded rather than hidden.** This file was reverted to an earlier committed
> version at least once overnight — a round of edits covering the PR #80 reconciliation, the V75 CI
> break and fix, and a correction to how BH-006 was reported were lost when a parallel session
> operating in this same shared directory checked out or wrote over this file while it sat uncommitted
> on disk. Content has been reconstructed from this session's own record and is committed at the end
> of this update specifically so it survives the next branch switch. See the changelog for what was
> lost and restored, and [[parallel-sessions-on-finora]] for the underlying pattern — this is its
> second occurrence, not its first.

This is the living plan. It is re-baselined whenever an engineering session, review, bug hunt or
deployment reports back — see §12 for the re-baseline procedure. Every number in it is derived from
the repository, not asserted: where a figure is an estimate rather than a measurement, it says so.

**One framing note that decides everything below.** `v1.0-import-reliability` was tagged at `75662b0`
on 2026-08-07. That tag is an **engineering milestone**, not a public launch. This plan is about the
*other* v1.0 — the one where a stranger can put their bank statement into Finora and the number that
comes out is right, and stays right. The two share a name and almost nothing else.

---

## 1. Where the project actually stands

| | |
|---|---|
| **Overall completion toward v1.0 GA** | **81%, no new figure asserted 2026-08-16 — see §12.** More unreviewed surface has landed since the last time this number was held for the same reason (Gmail C6.1/C6.2 implementation, the account-lifecycle trilogy, Transaction Explanation) — recomputing honestly needs the same security/architecture pass the 08-11 audit gave everything else, not yet done. Was (stated as) 83% on 08-11–08-14, 82% on 08-10, 65% at 00:42 on 08-09 |
| **Current phase** | Phase 4 effectively complete (BH-042/043 fully closed, BH-045 deliberately descoped, BH-044 confirmed built and closed 2026-08-16 — see §4/§12); Phase 5 (production readiness) **not started** |
| **Health** | **On Track on engineering, stalled on everything that actually sets the date** — see §8 and §12's 2026-08-16 entry |
| **v1.0 scope** | Web + admin portal + **mobile** (D-2, 2026-08-09) |
| **Open bug-hunt findings** | **0 Critical, 0 High from the numbered backlog.** BH-042 and BH-043 both fully closed (core fixes PR #127/#126, follow-up gaps PR #132/#133 merged 2026-08-16). BH-044 fully closed, engineering confirmed built and independently re-verified 2026-08-16 (PR #121, `52072e3`). BH-045 remains deliberately descoped by owner decision, not an open engineering item. **Real remaining gaps, both unticketed:** R-19 (`DashboardService`/`InsightsService` date-anchoring bugs for sparse histories, found during BH-042's review) and a Bank-entity audit-lookup gap found during BH-044's re-verification (§4) — neither has a BH-0XX ID or an owner decision yet |
| **Baselined against** | `origin/main` @ `d5aec3d` (re-baselined 2026-08-16; previous baseline `cc17716`/`22ffac7`, 134 commits behind). CI on the current tip fully green: Backend (Java 25), User frontend, Admin portal, Mobile (Expo), End-to-end smoke all `success`; nightly full E2E suite also green (03:51 UTC 08-16) |
| **Commits** | 750+ across 16 days (first commit 2026-07-31) |
| **Backend** | **2833/2833 tests green**, confirmed from the latest successful CI run's own log (not estimated). Up from 2191 at the last full count |
| **Clients** | frontend 36 test files · admin-portal 40 · mobile 30 · e2e 12 spec files — counted 2026-08-16 by file, not by assertion; not directly comparable to the earlier "122/105/88" figure, whose basis wasn't re-derived this pass |
| **CI** | green on `main`, self-hosted macOS runner, smoke E2E blocking on every PR |
| **Deployed** | Yes — `app.finoratech.info` (Cloudflare Pages, 200 OK) + Railway backend (`api.finoratech.info/actuator/health` → `UP`) + Railway Postgres. **New 2026-08-16: GitHub's own deployment status on the current main tip (`d5aec3d`) reads `"Deployment failed"` (Railway, 19:07:40Z)** — both endpoints respond, so production is very likely serving the previous successful build rather than the latest commit. Needs the owner's own Railway dashboard access to diagnose; see R-18 |
| **Store enrolment** | **Unchanged since 08-10, now confirmed unchanged through 08-16 as well — zero evidence of movement found on a full repo search.** Apple: submitted as Individual, still not confirmed complete. Google Play: still not started. Still the single most stagnant item on the entire plan, now stagnant for longer |
| **Production-readiness audit** | **Complete** (2026-08-11), **but its own scope is aging** — three feature areas have shipped since with no equivalent review: Gmail C6.1/C6.2 (new admin-facing OAuth-adjacent surface), the account-lifecycle trilogy (new deletion/export endpoints over the most sensitive data in the app), Transaction Explanation. 3 original items still need Sid directly — see §11 |

**The deployment is real but unpopulated.** `app.finoratech.info` is live, and the 2026-08-08
stale-chunk incident happened on it — but **D-1 is resolved: the only user is the owner, testing.**
No customer financial data exists in production.

Three consequences, all of them good, and worth stating because they are the difference between a
plan and an emergency:

1. Every finding in §4 is a **pre-launch defect**, not a live incident. They still block launch. They
   do not have a clock on them, and nobody is being harmed while they are open.
2. **No data repair is needed.** BH-003 corrupts `Account.balance` on re-import; with no real
   accounts, fixing the code is the whole fix. The same is true of BH-017 — no bank statements have
   accumulated in storage that a retention job would have to go back and delete. Had this gone the
   other way, both would have needed a backfill migration and a correctness proof over live rows.
3. The production environment is therefore a **rehearsal surface**, and should be used as one — it is
   the right place to drill the restore, run the load test and break things, precisely because
   breaking it costs nothing right now. That window closes the day the first real user signs up.

---

## 2. Completion by workstream

Weighted by contribution to a *safe* launch, not by task count. A workstream that is 90% done but
whose remaining 10% is the part that corrupts a balance is not 90% done for launch purposes.

| # | Workstream | Weight | Done | Contribution | What the remainder is |
|---|---|---|---|---|---|
| 1 | Core product (auth, ledger, accounts, budgets, goals, dashboard, reports, admin portal) | 20% | 95% | 19.0 | A few unmigrated TanStack pages. (Password-policy convergence — D-6 — checked 2026-08-14: no drift existed, nothing to converge) |
| 2 | Import pipeline (M1 reliability + M2 at-scale) | 20% | 78% | 15.6 | `PdfTableLocator` (1,358 lines) and `imports/product/` (14 classes) still **never reviewed** — the largest unquantified risk in the repo. A new, small, appropriately-scoped gap logged this morning (non-text header row on a real SBI statement), not built ad hoc |
| 3 | **Financial correctness defects** | 10% | **90%** ▲ | 9.0 | All six original P0s (BH-001/003/004/005/006 + BH-023) CLOSED–VERIFIED and merged, including a real defect found in BH-006's own fix and corrected same night (see §12 changelog). Remainder is Round 2's unreviewed surface, not open tickets |
| 4 | Security & privacy | 12% | 90% ▲ | 10.8 | BH-014, 017, 025, 032, 036, 037, 039, 046 all merged (three more confirmed closed 08-14 — see §4). Remainder is entirely non-bug-hunt now: no malware scan, no edge headers, no secret manager |
| 5 | Testing & QA readiness | 12% | 85% ▲ | 10.2 | BH-050, 053, 058 (swept) closed; suite at 2383+ tests. `e2e-nightly.yml` runs nightly and on-demand, confirmed green against a real triggered run (BH-048, §4) — the "never actually executed" framing carried in this row was itself stale, corrected 2026-08-14 |
| 6 | Infrastructure & production readiness | 14% | 57% | 8.0 | **Unchanged across multiple reports now.** No restore drill, no load test, no secret manager, V73/V74 never applied to a non-test database |
| 7 | Mobile app | 12% | 68% ▲ | 8.2 | **Apple enrolment submitted** (Individual) overnight — first real movement on the item flagged in every prior report. Google Play still not started. Still no confirmed run on a physical device |
| | **Total** | **100%** | | **80.8 → 81%** | **Corrected 2026-08-14** — the previous "81.6 → 82%" did not match the sum of this table's own rows (19.0+15.6+9.0+10.6+10.2+8.0+8.2 = 80.6, not 81.6), a pre-existing arithmetic error. Recomputed from scratch with row 4's update: the headline moves to 81%, net **down** from the previously stated 82/83% despite real additional progress, because that arithmetic error was inflating it by a full point |

**Mobile is in v1.0** (D-2, 2026-08-09). Its 12% weight stays, and workstream 7 is now on the
critical path rather than beside it — see §5 and §9.

**The pattern holds, report after report.** Defect and infra-hygiene work keeps closing — six more
findings and a CI break fixed within the hour, overnight — and it keeps not moving the date, because
the date was never gated on it. Workstream 6 (infrastructure) has now shown the identical number
across three consecutive re-baselines. That is the clearest evidence available that store enrolment
and device bring-up, not code, are what determine when this ships.

**Velocity.** 489 commits / 9 days ≈ 54 commits/day; the last three days ran 105, 106 and ~40, at
5–7 merged PRs/day. Measured in the unit that matters — *closed workstream items per day* — recent
velocity is **≈1.5–2 items/day for design and tooling work, and an estimated 2–3 defects/day for
remediation with tests**. The second number is an estimate; this project has not yet done a
remediation sprint to measure it against.

**Capacity: ~10 hours/day** (stated 2026-08-09). This does not change the estimates, and it is worth
saying why rather than letting it look like an oversight. The velocity above was measured from days
that produced 105 and 106 commits — those were not four-hour days. **10 h/day is the input that
produced the baseline**, so confirming it *validates* the working-day figures rather than moving
them. What would move them is a change in either direction from that pace.

One caveat on the unit. A "working day" below means one 10-hour day of focused output at the observed
rate. Defect remediation with tests is slower per hour than the design and tooling work most of the
measured history consists of, which is why block A and B carry the widest ranges.

---

## 3. Phases

| Phase | Name | Status | Evidence |
|---|---|---|---|
| 0 | Scaffold & core product | ✅ Complete | Every page wired to a real backend |
| 1 | Architecture hardening (versioning, envelope, soft deletes, audit, RBAC, refresh tokens) | ✅ Complete | `ApiResponse<T>`, `AdminRbacIT` |
| 1.5 | Intelligence layer (Ask Once, recurring detection, merchant map) | ✅ Complete | `CsvImportServiceAskOnceTest` |
| 2 | **M1 — Import Reliability** | ✅ Complete | Tagged `v1.0-import-reliability`, 1,510 tests green on a fresh clone |
| 3 | **M2 — Import at Scale** | 🟡 ~80% | Items 1–6 built (corpus gate, layout registry V68, WI1A, multi-account parity, async completion, observability V72). Items 7–8 open |
| 3.5 | Security & privacy cleanup | ✅ Complete | PII sanitization swept, corpus scanner, tree ratchet 145→112, security control audit accepted |
| **4** | **Hardening & Defect Remediation** | 🟡 **~98% — effectively complete, 1 item remains** | Corrected 2026-08-14, updated 08-15, corrected again 08-16 — all P0–P3 findings closed, accepted, or decided (§4). **`BH-044` confirmed fully closed 2026-08-16** (decision + engineering both shipped, PR #121 `52072e3`, independently re-verified — this row previously said the opposite, contradicting §1's already-correct note; see §12 changelog). `BH-045` remains deliberately descoped by owner decision, not an open engineering item. Not marked ✅ Complete only because the Bank-entity audit-lookup gap BH-044's re-verification surfaced (§4) is new and undecided |
| 5 | Production readiness | ⬜ Not started | Backups, DR drill, load test, runbooks, scaling decision |
| 6 | Beta | ⬜ Not started | Gate: Phase 4 + 5 complete |
| 7 | v1.0 GA | ⬜ Not started | Gate: §10 release criteria all met |
| — | M3 — Document Intelligence (ADR-004/005, ground-truth model, OCR, RD/FD extraction) | 🟡 Design complete, **not implemented, and correctly so** | ADR-005 §10 forbids implementation before the ground-truth model exists |
| — | Gmail Sync (C1–C5.4: OAuth, sender trust gate, discovery, 6 merchant parsers, connection UI + review queue) + **C6 Sprint 1** (C6.1 completion + C6.2 Merchant Intelligence Dashboard) | ✅ **Complete** — C5.4 merged 2026-08-15 (PR #122); **C6 Sprint 1 merged 2026-08-15 (PR #124, PR #128)** | PRs #104–#122, #124, #128, all merged. Same "grew outside the phase table" pattern §12 already flags for other unweighted work — not yet in §1/§2, security review still owed (see 08-15 changelog). **D-17 (2026-08-15) overrides D-15/D-16: C6 began before GA, running in parallel with launch blockers.** Scoped to Sprint 1 only — C6.1 completion (sync-health/receipt-count visibility, review-queue confidence + reasoning) and C6.2 (Merchant Intelligence Dashboard, admin-only) — both now shipped. C6.3/C6.4/C6.5/C6.7 remain held; C6.6 (OAuth verification) stays a launch requirement, not C6 scope |

---

## 4. The defect backlog — what Phase 4 actually is

Source: [`reviews/2026-08-08-repo-wide-bug-hunt.md`](../../quality/bug-reports/2026-08-08-repo-wide-bug-hunt.md), plus
two remediation rounds since. **This describes the original open backlog; it is the historical record
closures are graded against, not current status** — current status is §1 and §2.

### P0 — CLOSED, all six, all CLOSED–VERIFIED and merged to `main`

| ID | Defect | Closed by |
|---|---|---|
| BH-001 | Cancelling an in-flight import **un-cancelled it**; the job re-queued and staged anyway | Round 1 (PR #63) |
| BH-003 | Re-importing a statement **moved the account balance twice** | Round 1 (PR #63) |
| BH-004 | `ClosingBalanceGuard` applied the asset formula to **every** account type | Round 1 (PR #63) |
| BH-005 | A refunded purchase was reported as a **pure loss** | Round 1 (PR #63) |
| BH-006 | `confirmReimport` accepted arbitrary client rows with **no staged-row check** | PR #75 — and its own fix shipped a real defect, corrected same night. See §12 changelog; do not treat this row as "closed and done" without reading it |

All five carry regression tests mutation-checked against the restored defect. These survived a
1,745-test suite and 489 commits before being found — none had a test at the time.

### P1 — no open defects, one accepted trade-off (not a defect)

**Closed:** `BH-002`, `BH-011`, `BH-012`, `BH-013` (Round 1) · `BH-019`, `BH-023`, `BH-026`, `BH-027`
(financial/idempotency) · `BH-017` (retention, merged) · `BH-025` (BYTEA dual-write, merged) ·
**`BH-048`**, **`BH-007`**, **`BH-060`** (see below).

**`BH-048` — CLOSED–VERIFIED, 2026-08-14.** Its "never executed" framing was stale (the workflow had
actually run 5 times); the real defect was two consecutive nightly failures (08-12, 08-13),
root-caused to `0fba684`/`62a112c` (08-11) changing the import review table and `DuplicateReview`
panel to `DD-MMM-YYYY` dates without updating the E2E assertions checking the literal rendered text —
missed because those specs only run nightly, not in the PR-blocking smoke suite. Fixed in
[PR #88](https://github.com/siddharth705/finora/pull/88) (merged as `bd5dcd2`), which also picked up
an unrelated but blocking `nanoid` advisory (`GHSA-2v37-7h3g-55p8`) on `mobile/` via a same-range
patch bump, `3.3.17` → `3.3.18` — transitive-only, no risk-acceptance call needed. **VERIFIED, not
just REVIEWED:** a manual `workflow_dispatch` of `e2e-nightly.yml` against `bd5dcd2` (the merge
commit itself, not a later commit) completed `success` — [run 31774202063](https://github.com/siddharth705/finora/actions/runs/31774202063) <!-- synthetic-ok: public GitHub Actions run ID, not customer data --> — the actual break was
demonstrated (two real scheduled failures) and then demonstrated gone on the real workflow, not
inferred from the PR's own smoke job.

**`BH-007` — CLOSED–VERIFIED, 2026-08-14.** Re-verified against current code first (line numbers had
shifted since the 08-08 report, from BH-041/BH-044 both touching `ReconciliationService.java`) —
confirmed still reproducing: the refund pass's only amount guard was per-pair
(`income.amount <= expense.amount`), so N income rows each ≤ one EXPENSE could each independently
match it, silently excluding real income from every total. Fixed in
[PR #89](https://github.com/siddharth705/finora/pull/89) (merged as `0d15f74`): tracks cumulative
refund capacity per expense across the pass, seeded from already-resolved `REFUND` rows so it holds
across separate runs too, not just within one — proven complete for both `reconcileForUser`
(unbounded) and `reconcileForImport` (windowed) via the existing
`CANDIDATE_WINDOW_DAYS >= REFUND_WINDOW_DAYS` invariant, no new query needed. **VERIFIED, not just
REVIEWED:** two new regression tests mutation-checked via `git stash` on just the source fix — both
confirmed to fail against the pre-fix code with the exact reported symptom (`expected: OK, but was:
REFUND`), then confirmed passing with the fix restored. Full backend suite green (~2355 tests).

**`BH-060` — CLOSED, ticketed 2026-08-16, closed same day on re-verification.** Ticketed as a live
"`OPENING BALANCE`/`CLOSING BALANCE` marker rows can be confirmed into the ledger as real `EXPENSE`
transactions" defect, describing no marker-row exclusion anywhere in the pipeline. **Before starting
any fix work, re-verified against current `main` and found the ticket describes a bug that was
already fixed five days earlier** — [`ade05ca`](https://github.com/siddharth705/finora/commit/ade05ca)
(2026-08-11, "stop statement marker rows from becoming ledger transactions") adds `RowKind`
(`TRANSACTION`/`BALANCE_MARKER`, classified structurally by which amount column resolved a value,
never by description text) and excludes `BALANCE_MARKER` rows from staging, verification, and
confirm in both `PdfPreviewGenerator` and `PreviewGenerator`. The ticket's own investigation had
`ade05ca` in its git history when written and still asserted the pre-fix behavior — no clean
explanation found for the discrepancy, but the empirical evidence is unambiguous: the 94 tests
`ade05ca` added or updated (including `PdfPreviewGeneratorTest`'s golden-fixture assertion, changed
from `hasSize(6)` to `hasSize(4)` in that same commit) all pass on current `main`. Also checked the
ticket's own defense-in-depth question (`ImportService.persistSection()` has no marker check of its
own) and found it's a non-issue: both real confirm entry points (`confirmSession`,
`confirmReimport`) run `ConfirmedRowIntegrity.requireSameRows()` (built for the unrelated BH-006/
BH-023 fabricated-row bug) before `persistSection()` ever runs — a multiset check of the client's
confirmed rows against the server's own staged/re-parsed rows, which never contain a marker row to
begin with. No caller of `confirm()`/`confirmSession()` skips this check. Closed as
[issue #138](https://github.com/siddharth705/finora/issues/138) with full evidence in the closing
comment; full original writeup (now describing an already-fixed state, kept for the trail) at
[`marker-row-pollution-scope-investigation.md`](../../architecture/system-design/marker-row-pollution-scope-investigation.md).

**Still open:**
- **BH-054** — accepted trade-off, not a defect.

### P2 — After the critical path

The 24 Medium findings: performance (`BH-041`–`046`, `055`–`057` — eight services each load the
user's entire transaction history), privacy/retention (`BH-039`, `BH-044`), operability
(`BH-008`–`010` returning 500 where they should return 4xx), test infrastructure (`BH-053`), and
the docs-vs-code lies (`BH-018`, `BH-021`, `BH-022`) — a comment asserting a guarantee the code
does not have is worse than silence, because it stops the next reader checking.

**`BH-053` — CLOSED–VERIFIED, 2026-08-14.** The check-then-act race in
`MerchantLearningService.confirm()` against V7's `UNIQUE(user_id, merchant_id, category_id)` --
the codebase's own most carefully self-documented open defect, complete with a pre-emptive warning
against the tempting wrong fix (`REQUIRES_NEW`, rejected because the row's foreign keys routinely
point at parent rows the caller's own uncommitted transaction just created). Closed in
[PR #92](https://github.com/siddharth705/finora/pull/92) (merged `c8bc96a`) with a native
`INSERT ... ON CONFLICT DO NOTHING` upsert that stays inside the caller's transaction, honouring
the same FK-visibility constraint the rejected fix would have violated. **VERIFIED:** the existing
regression test only proved the race existed; rewritten against real Postgres to prove it's closed
(one caller's transaction held open past its insert, a second genuinely blocked at the database,
both resolving correctly), mutation-checked against the pre-fix code (failed with the exact
predicted `duplicate key value violates unique constraint`), and the two existing propagation-
contract tests confirmed unchanged.

**`BH-018` — CLOSED–VERIFIED, 2026-08-14. Both halves now closed.** The transaction-boundary claim
closed earlier; the "Also here" memory-materialization note (`file.getBytes()` holding up to 10 MB
on the heap per upload, ungated against concurrent uploads) closed in
[PR #93](https://github.com/siddharth705/finora/pull/93) (merged `02d9d287`). Turned out larger
than one call site — `StatementStorage.store(byte[])` is the whole interface, not just
`ImportJobService.accept()`'s use of it — but scoped additively: a new `store(InputStream, long)`
overload is now the one real implementation per backend (filesystem, R2), with `store(byte[])`
becoming a default method wrapping the array, so the two callers that already hold content in
memory for parsing reasons (`ImportService.persistSection`, `ImportSessionService.storeContent`)
stay untouched rather than being converted for no benefit. **VERIFIED:** a new test proving the
actual property (content never fully buffered before being written onward) went through its own
mutation-check refinement — tracking `InputStream.read()` chunk sizes turned out not to
distinguish real streaming from `readAllBytes()`, which also reads in bounded chunks internally;
tracking `OutputStream.write()` sizes does, since a naive reimplementation still writes the whole
buffered result in one call. Confirmed against a deliberately reverted mutation before restoring
the fix. Full backend suite green both before and after rebasing onto unrelated same-day import
work, with no line-level overlap.

**The BH-058 class of test defect — CLOSED, swept, 2026-08-14.** Named after `MerchantLearningQueueIT`
was demonstrably broken by an unrelated import-heavy test, then flagged as "not swept for other
instances" in every closure report since (Round 1, the medium/low pass, this plan). Actually swept
this time in [PR #95](https://github.com/siddharth705/finora/pull/95) (merged `2c40ffa9`): one real
occurrence found (`ImportJobStoreIT`'s recovery tests asserting exact counts on the table-wide
`recoverAbandoned()`), fixed using the same noise-fixture technique the original established, plus a
systematic search across every table-wide repository method and its test callers in the rest of the
suite. No other genuine instances found — this codebase already carries substantial deliberate
hardening against this exact defect class from prior work. This closes the last open P2 test-
infrastructure item.

**Process note, recorded rather than hidden: PR #95 needed an admin-override merge.** `Backend
(Java 25)` failed 4 consecutive times on `AcquisitionWiringIT`, always the identical
`Connection to localhost:5432 refused` on `actions-runner-2` — confirmed, before overriding, that
the same failure independently hit `main` itself the same day on two pure docs-only commits
(`cddc809`, `c8bc96a`) that could not possibly cause a real backend test failure, and that
`ImportJobStoreIT` (this PR's actual change) passed cleanly in all 4 attempts. Merged past the
failing check on the owner's explicit instruction once the failure was established as a pre-existing
runner-level infrastructure issue, not a code defect. The underlying cause was fixed independently
and merged immediately after (`ba33253`, "give AcquisitionWiringIT a real test datasource"),
confirming the diagnosis.

**`BH-039` — CLOSED–VERIFIED, 2026-08-14. Investigated, no live defect found; regression coverage
merged.** The finding's own
words: content addressing being global across tenants "becomes a cross-tenant defect the moment the
future sweep is built and reference counting is not per-object-global." That sweep has since been
built (BH-017) — the trigger condition already occurred. Checked directly against the real generated
SQL: the sweep's reference counting already IS global — `existsByObjectKey` on both
`StatementImportRepository` and `ImportSessionRepository` takes no `userId` at all, so it cannot be
scoped to one tenant. **Not a bug — the warned-about trap was avoided when the sweep was built**,
most likely because the shared-object design was already documented and front of mind. What was
missing was regression coverage for specifically the cross-tenant case (the existing "object
survives" tests both use one fixture user for both sides, proving "respects any reference" without
being able to tell that apart from "respects any reference from this tenant"). Added in
[PR #96](https://github.com/siddharth705/finora/pull/96), with an explicit warning comment on both
`existsByObjectKey` methods naming the exact future refactor (adding user-scoping because the query
"looks" under-scoped) that would silently reintroduce this as live cross-tenant data loss.
Mutation-checked: the new test and the pre-existing same-tenant test both correctly failed against a
simulated user-scoped version, with the log showing the shared object actually destructively swept
despite a live reference. [PR #96](https://github.com/siddharth705/finora/pull/96) merged
(`8abfe074`), full backend suite green (no `AcquisitionWiringIT` recurrence — confirms `ba33253`'s
fix held).

**Follow-up, same day (`a5365dd`):** the sweep's reference check is an OR across two tables —
`statementImportRepository.existsByObjectKey(...) || importSessionRepository.existsByObjectKey(...)`
— but PR #96's regression test's surviving reference was a `StatementImport` row, so the first
clause alone kept the object alive and the `ImportSessionRepository` half of the OR was never
actually exercised. Added
`sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveImportSession`, where the surviving
live reference is another tenant's still-staged `ImportSession` instead — this reaches the OR's
second clause under real code, not just under mutation. Mutation-checked the same way: temporarily
scoped `ImportSessionRepository.existsByObjectKey` to always return false (the same "add userId, it
looks under-scoped" mistake the warning comment names), confirmed both this test and the
pre-existing same-tenant `ImportSession` test fail, reverted, confirmed green (7/7). Committed
directly to `main` (no PR). Both halves of the sweep's cross-tenant guard now have regression
coverage.

**Plan-drift correction, 2026-08-14.** Checking what the "next open item" actually was surfaced that
this plan had been describing six already-fixed findings as open — the code was fixed (by prior
sessions) but the closure was never recorded here. Re-verified each directly against current code and
its test suite rather than trusting the 08-09 report forward:

- **`BH-014`** (lockout leaked account existence, and then leaked it via timing after the status
  code was fixed) — **closed.** A locked account now returns the same 401/"Invalid credentials" as a
  wrong password, with a discarded `passwordEncoder.matches` call against a throwaway hash so the
  ~4 ms lockout-check path costs the same as the ~260 ms BCrypt path. Dedicated suite
  `LoginExistenceOracleIT`, 4/4 green.
- **`BH-029`** (parser format decided twice — once at upload, once in the worker — agreeing only by
  construction) — **closed.** Decided once at upload, written to `import_jobs.source_format`, read
  from there by the worker. `ImportJobSourceFormatIT` + `StatementUploadTest`, green.
- **`BH-032`** (prod DB-password validator only caught the literal string `"finora"`, despite its
  own message claiming to catch "unset" too) — **closed.** Checks blank/unset separately, plus a
  case-insensitive list of well-known default passwords. `ProductionConfigValidatorTest`, green.
- **`BH-036`** (CORS listed `X-Request-Id` in neither `allowedHeaders` nor `exposedHeaders`, so
  `CorrelationIdFilter`'s echo-back couldn't work cross-origin in either direction) — **closed.**
  Added to both. `CorrelationIdCorsContractTest`, green.
- **`BH-037`** (`docker-compose.yml` published Postgres on `0.0.0.0:5432` with `finora`/`finora`,
  reachable on any network the host joined) — **closed.** Bound to `127.0.0.1:5432:5432` — no
  regression test possible for a network-binding config change, verified by reading the compose file
  directly.
- **`BH-046`** (dual write to `file_content` had no trigger to ever stop, because the two phases that
  were meant to end it — backfill, then column drop — both quietly never happened) — **closed**,
  alongside BH-025: a new upload writes to object storage *or* `file_content`, never both.
  `ProductionConfigValidatorTest`, `ImportSessionServiceTest`, `ImportServiceStorageDualWriteTest`,
  green.
- **`BH-044`** (`audit_logs` grows unbounded, and a `RECONCILIATION_RUN` row per write was a large
  share of that growth) — **growth-rate half fixed** (no row written for a run that reclassified
  nothing). **Retention direction decided 2026-08-15: redact.** Owner's call: keep the audit event
  (actor, action, entity, timestamp, correlation ID — proof something happened) indefinitely;
  redact the financial payload after a defined window, rather than truncating the row entirely or
  keeping it forever. **Engineering CLOSED — confirmed built 2026-08-16, previously mislabeled
  "not yet built" in this section while §1 already had it right.** Shipped as PR #121
  (`52072e3`), independently re-verified this pass, not just re-quoted: `V89__audit_log_redaction.sql`
  adds `audit_logs.redacted_at`; `AuditService.scheduledRedaction()`/`redactExpiredMetadata()`
  batches expired rows (`app.audit.redaction.retention-days`, default 730, 90-day floor), wipes
  `metadata` to `{"redacted": true}`, stamps `redactedAt`, per-row try/catch so one failure doesn't
  abort a batch; `AuditServiceTest` + `AuditServiceRedactionIT` both exist and pass. Built as
  **in-place redaction on `audit_logs` itself** — the migration's own comment states explicitly this
  is not the owner's originally-proposed separate `audit_payloads` table, a design change made
  during implementation, not flagged as a decision anywhere in this plan until now.
  **New, undecided gap found during this re-verification:** `Bank` entities use a string natural
  key, so `BANK_CREATED`/`UPDATED`/`DELETED` audit events store the real bank ID inside `metadata`
  (not `entityId`) — the one field redaction wipes. The admin Bank audit tab's only lookup path
  (`AuditLogRepository.findByBankIdInMetadata`) becomes permanently unable to find a bank-related
  audit row once it redacts. Flagged in `AuditService.java:54-63`'s own doc comment, never
  surfaced in this plan, not yet decided or fixed.

`BH-042`/`043`/`045` (the remaining performance cluster) untouched — still owned by a parallel
session per §1. With this correction, **there is no actionable engineering item left in P2 that
isn't already decided** — BH-044's mechanism is chosen; building it is a new, unscoped item, not an
open decision.

**Update, 2026-08-16 re-baseline.** The parallel session's work landed: **BH-042 partially closed**
(PR #127, `merchantTrend` moved off all-time history onto its own bounded window), but that PR's own
scope note deliberately left `DashboardService`/`InsightsService` untouched after "a 3-pass code
review found real correctness bugs in their date-anchoring logic for users with sparse/gappy
transaction histories" — **a real, currently unticketed finding**, living only as a PR footnote, not
a bug-hunt item with its own ID (still open, see R-19 in §7). A post-merge review then found PR #127's
own scope note was wrong about one more call site (`topMerchants`/`topCategories`'s `month != null`
case, a real admin API parameter, still called the unbounded `findByUserId`) — fixed in **PR #132,
merged 2026-08-16T04:02:47Z (`ffa6ad3`)**. **BH-043 core-closed** (PR #126, import bursts rejected
instantly instead of blocking Tomcat threads); its defense-in-depth + test-quality follow-up **PR
#133 also merged 2026-08-16T04:06:27Z (`a77cca7`)**. **BH-042 and BH-043 are now both fully closed** —
the "open, unmerged" status recorded earlier the same day is stale as of this correction. **BH-045
remains fully untouched** — confirmed via `2026-08-09-medium-low-closure-report.md`: "DESCOPED
mid-run... NOT ATTEMPTED... removed from this pass mid-run by the repo owner." Worth a direct
reconfirm that's still the intent, given how much of the rest of this cluster just got swept up
around it.

**Second update, same day (status-report re-baseline).** One more real, currently unticketed
defect surfaced, more severe than R-19: an investigation into merged-cell PDF extraction
(uncommitted working note, not yet a bug-hunt item) found that `OPENING BALANCE`/`CLOSING BALANCE`
marker rows carrying a real date can be imported as a real EXPENSE transaction equal to the account's
opening/closing balance — **on both the PDF and CSV import paths**, live in production today. This is
a financial-correctness defect (a phantom transaction distorting the ledger), not a display bug like
R-19, and doesn't have a BH-0XX ID yet. Needs triage and a ticket before P2 can honestly be called
fully closed. Also found: PR #97 (regression tests upgrading BH-008/009/010 from CLOSED–REVIEWED to
CLOSED–VERIFIED) has been open, green, and mergeable since 2026-08-14 with zero reviews — sitting idle,
not blocked on anything.

**`BH-061` — OPEN, ticketed 2026-08-16.** New finding from a PM-directed `ProductIdentityResolver`
audit (three phases: core-logic read, an integration test proving the MATCHED path end-to-end, then
real-corpus validation). The audit's original question — can a composite statement's sections
(Savings/RD/FD sharing one relationship number) get silently merged into each other — came back
clean: zero cross-section collisions across all 21 real statements / 32 sections in the corpus. What
it found instead: 26 of those 32 sections (81%), and **100% of the corpus's credit-card sections (6
files, 13 sections)**, never receive a usable account/card number at all, so `ProductIdentityResolver`
can never recognise a re-import of them — every re-import silently creates a new duplicate account
instead. Not a financial-correctness defect (no balance or transaction total is ever wrong; the
failure mode is an extra, correct, duplicate account, never a wrong merge) and not a
`ProductIdentityResolver`/`ProductIdentity` code defect — both were read line-by-line and confirmed
to behave correctly on the inputs they receive. Root cause is upstream, in `PdfMetadataExtractor`'s
account/card-number extraction — part of the PDF extraction subsystem this plan's own §4 already
lists as never reviewed line-by-line. Full evidence, methodology and the 6/32 baseline a future fix
should move:
[`product-identity-coverage-gap-investigation.md`](../../architecture/system-design/product-identity-coverage-gap-investigation.md).
Not scoped or started here, per explicit instruction to keep the audit at the identity-resolution
boundary rather than expanding into extraction.

### P3 — v1.1 (label stale — pulled into v1.0 scope 2026-08-09, see §5)

The 18 Low findings, the Layout Curation UI (M2 item 7), Merchant Intelligence Workbench (WI4A),
cross-user merchant intelligence, Excel export.

**All 18 Low findings checked, 2026-08-14 — same audit pass as P2's correction above.** None are
open engineering work:

- **Closed** (verified earlier rounds, 08-09): `BH-008`, `BH-009`, `BH-021`, `BH-022`, `BH-028`,
  `BH-030`, `BH-047`, `BH-055`, `BH-056`, `BH-057`.
- **Closed today** (same fixes recorded in P2 above, since these findings span both buckets):
  `BH-029`, `BH-032`, `BH-036`, `BH-037`, `BH-039`, `BH-046`.
- **Accepted — real, understood, deliberately not being changed**, per the 08-09 closure report's
  own §8: `BH-035` (`X-Forwarded-For` last-hop trust assumes exactly one proxy) and `BH-054` (a push
  to a branch with no open PR gets no CI).

That's all 18. **The entire bug-hunt defect backlog, P0 through P3, is now closed or accepted**
except the two items named at the top of this section: `BH-044`'s retention half (owner decision)
and `BH-042`/`043`/`045` (owned by a parallel session, in progress elsewhere).

### Not in the bug hunt but release-blocking

- **Malware scanning is absent** (`security-control-audit.md`) — zero matches for any scanner across
  `backend/src`, and uploads are the largest untrusted-input surface in the product.
- **No security headers at the CDN edge** — `frontend/public/_headers` sets only `Cache-Control`.
- **Masking has no enforcement** — three log sites are masked because they were fixed by hand.
- **No secret manager** — every production value is a Railway/Cloudflare environment variable. One
  compromised Railway login is every backend secret at once.

---

## 4a. Statement Intelligence — C-8 Evidence Model & Parser Reliability (new, 2026-08-16)

**Why this section exists.** This entire workstream had zero references anywhere in this living plan
until today, despite being real, active engineering — tracked only in `docs/architecture/adr/`,
`docs/architecture/system-design/`, and agent memory, most of it never even committed. That gap is
what let a stale label ("C-8 Track B completion") sit in this file's own 2026-08-16 changelog as a
supposed remaining safety-gate item for **five days after it had actually closed** (`ade05ca`,
2026-08-11) — nobody checking this file could have known, because the file didn't track it. This
section is the fix: fold C-8/parser status in, and keep it separate from actual launch blockers so
the two stop getting conflated.

**Not a v1.0 release gate.** Nothing below is in §10's release criteria. This is statement-extraction
quality/reliability work — real, ongoing, but a different question from "can we launch."

### C-8 Evidence Model (ADR-006) — when to trust extraction enough to skip OCR

| Item | What it is | Status |
|---|---|---|
| **Track A #2** | Header-vocabulary synonyms (e.g. "Balance Forward" vs "Opening Balance") | 🔴 Open — blocked on real corpus, not engineering. Two synthetic-corpus measurement passes explicitly exhausted (redactor-bias, then author-bias ceiling); only genuinely new real statements can close it |
| **Non-ledger #3** | Non-ledger table detection (e.g. FD schedules, interest tables) reliability | 🔴 Open — same corpus blocker as Track A #2 |
| **Track B** | Marker-row pollution — `OPENING BALANCE`/`CLOSING BALANCE` rows staged and persisted as real transactions | ✅ **Closed 2026-08-11, `ade05ca`.** `RowKind.BALANCE_MARKER` classification, structural (which amount column resolved a value), not description-text matching — generalizes across wording. Confirmed still live and unmodified in current `TransactionNormalizer`/`PdfPreviewGenerator`/`PreviewGenerator` as of this re-baseline. **This is the item that was mislabeled as an open gate on 2026-08-16 — see the changelog correction below** |
| **C-8.3** | OCR routing trigger — when extraction evidence is insufficient enough to invoke OCR | 🔴 Blocked, not close. The originally-proposed composite trigger was investigated and explicitly rejected (missed a real class-2 failure, 3 false-positive mechanisms); its test-scope code was written then deleted the same day. Reframed, still 5 open questions, nothing wired into `RoutingTextAcquirer` |
| **R2** | Independent closing-balance source via `StatementSummaryExtractor.PrintedSummary`, to make cross-source corroboration non-circular | 🔴 Deferred, untouched — same corpus blocker |

### Parser Reliability Milestone 1 — real-corpus-driven correctness fixes

Surfaced by a large real-statement corpus sweep (16 savings + 6 credit-card documents, multiple
banks) run 2026-08-12. Tracked as a "Parser Improvement Board" that has never existed as a committed
doc — status below is freshly re-verified against git, not carried forward from a snapshot.

| ID | Root cause | Banks affected | Status |
|---|---|---|---|
| **P-001** | Wrapped/split headers (horizontal run-split + vertical 2-line admission) | HDFC, CBI | ✅ Closed — `2bcb21e`, `701df9b` |
| **P-002** | Section over-segmentation → phantom accounts (missing zero-extraction guard + prose mistaken for headers) | Kotak, SBI, AU, HDFC-CC | ✅ Closed — `98560ef`, `c5c4e0f` |
| **P-003** | Duplicate column names silently collide, one amount column lost | ICICI | ✅ **Closed — `f9ea543`** (`resolveDuplicateColumnNames`, `PdfTableLocator.java:1482-1556`). Not previously tracked as closed anywhere; found during this re-baseline |
| **P-004** | Reverse-chronological ledgers misread as forward, corrupting balance-chain validation | PNB, Bandhan | ✅ **Closed — `282fe5b`** (direction-detected reversal, `BalanceChainValidator.chronological()`) **+ `42543cd`** (a separate root cause on the same document: wrapped narration swallowing PNB's running-balance cell). PNB now fully VERIFIED across all validators. Not previously tracked; found during this re-baseline |
| **P-005** | Trace-capture redactor destroys textual (non-numeric-separator) dates, blocking evidence capture for any bank using them | HSBC, Kotak, AU | 🔴 **Still open.** No commit touches this since 2026-08-12; `PdfTraceRedactor`'s `DATE_LIKE` regex still only matches numeric-separator dates |
| **P-006** | SBI ledger rows entirely unrecognized (zero sections located) | SBI | ✅ **Closed — `89de6b9`** (new `INFERRED_HEADERLESS_LAYOUT` capability, `PdfTableLocator.java`). Verified against the real SBI document: 6/6 transactions extracted, all validators VERIFIED. Not previously tracked; found during this re-baseline |

**Net effect of this re-baseline: 5 of 6 Parser Reliability items are closed, only P-005 remains —
materially better than what any prior snapshot recorded, because three closures (P-003, P-004, P-006)
had never been checked off anywhere.** This is the same "unreviewed surface lowers completion until
checked" principle in reverse: unchecked *closed* work doesn't raise anything either, until verified.

**Standing backlog, still open, none touched:** HDFC merged-cell extraction (confirmed production
already handles it correctly, per the trace-tooling investigation closed 2026-08-11 — not a code
gap); `"-"` placeholder noise in unparseable routing (cosmetic); CSV plural-header gap in
`AMOUNT_HEADER_HINTS`.

---

## 5. Critical path

**Superseded by D-11 (2026-08-15) — restructured below, dates not yet recalculated.** The diagram
this replaced treated mobile as one combined track converging on a single `v1.0 GA`, which assumed
simultaneous launch. D-11 reversed that: iOS launches first. The structure is corrected here; the
Best/Target/Conservative dates in §9 are not — they still assume the old, simultaneous-launch shape
and need their own recalculation pass, deliberately not done yet per the owner's instruction not to
invent dates ahead of D-7's pricing scope.

Three tracks now: web (shared backend/API, gates both platforms), iOS (decoupled, launches first),
and Android (trails, still gated by its own tester clock — unaffected by D-11, see §9a).

```
web    P0 financial + async defects → P1 security & idempotency → full E2E in CI
                    → backup/restore drill + load test ──────────────────┐
                                                                          │
iOS    Apple enrolment + EAS + iOS device bring-up (APNs)                │
                    → duplicate-review parity → iOS E2E                 ├→ D-7 pricing/ToS finalized
                    → store listing + privacy policy (D-12) ─────────────┤   + D-12 contact resolved
                    → App Store review ──────────────────────────────────┤   → iOS public launch
                                                                          │
Android store enrolment (done) + EAS + Android device bring-up          │      (independent milestone,
                    → 12 testers × 14 continuous days → Play review ────┘       trails iOS — §9a)
```

**Two new blockers sit directly on the iOS path that didn't before D-11 and D-7 reversed:**

- **D-7 (pricing/ToS)** was scoped to v1.1 — zero impact on this critical path. Reversed 2026-08-15:
  now required before *production launch*, and with iOS launching first, that means before iOS's
  launch specifically, not some later combined date. No pricing model exists yet (see §11), so this
  is currently an **unscoped, unestimated blocker** on the nearer platform's launch, not a distant one.
- **D-12 (privacy-policy data controller)** already blocked both stores' listings before today. What
  changes is *when* it bites: previously it could hide behind Android's ~3-week tester-gate clock;
  now, with iOS decoupled and un-gated by that clock, D-12's own timeline (holding for a lawyer's
  view, per the owner's 2026-08-15 decision) is directly on the critical path to first launch.

**What does *not* change:** the web backend is still a shared dependency — both platforms call the
same API, so P0/P1/security/E2E/production-readiness work on the web track still gates either mobile
platform launching, exactly as before. iOS-specific mobile work (M0–M3, M5, M6 from §9's Mobile
track) still applies to iOS; only Android's tester-gate (M4's cross-platform parity work still
applies to both) stops being something iOS has to wait behind.

**The sequencing rule this implies, updated:** start Apple enrolment and iOS device bring-up first —
their surprises have long tails, same reasoning as before. Android enrolment is already done (§1);
Android device bring-up and the tester-gate clock can run in parallel without blocking iOS, and
should still start early so Android doesn't trail further than the gate itself requires (§9a).

Everything else runs beside it or waits. Specifically **off** the critical path today:

- **Document Intelligence (M3)** — ADR-004, ADR-005, the ground-truth model, OCR, RD/FD extraction,
  and the wrapped-header parser work currently on `fix/wrapped-header-column-anchors`. This is
  excellent work and it is *ahead of* the launch, not on the way to it. See §8.
- **Layout curation UI** (M2 item 7) — a finishing feature; the registry table it needs already exists.
- **Merchant Intelligence Workbench**, cross-user merchant intelligence — M3 by charter.
- **11 open Dependabot PRs** — batch them in one sitting; they are not a workstream.

**C6 Sprint 1 (D-17, 2026-08-15) was a deliberate exception to "off the critical path" — now closed.**
The owner explicitly overrode D-15/D-16 and accepted the GA-timeline risk of running it in parallel
with everything on this critical path, not behind it. Scoped narrowly: C6.1 (finish the Gmail
connection/review UX, PR #124) + C6.2 (Merchant Intelligence Dashboard, admin-only, PR #128) — both
merged same day. Touched none of this diagram's own code areas (Settings.tsx, GmailReview.tsx, a
new admin analytics endpoint vs. the web/iOS/Android launch tracks), so it added engineering-time
risk to the GA date (the same person's hours split two ways) without adding a technical dependency
between them — and that risk is now realized-and-closed rather than open. C6.3 (unknown-merchant
learning), C6.4 (reconciliation), C6.5/C6.7 (already have their own proposals) stay held, per D-17's
own scoping — no Sprint 2 has been decided yet.

---

## 5a. Production-readiness resequencing (2026-08-14)

Following the architecture/production-readiness audit, the owner re-sequenced the resulting gap list
against one constraint the flat gap list didn't account for: **Railway Pro is planned**, and doing
full production-grade DR work against infrastructure that is about to change risks rework. The
sequencing below is a decision, not a scope cut — everything demoted stays tracked.

**Removed from P0 (immediate blocker) status:**
- **Backup/restore verification** — no longer gates v1.0. Reason: Railway Pro will materially change
  production capabilities; the team isn't finalizing production ops yet; drilling DR now would likely
  be redone once the infra changes. See R-4.

**Update, 2026-08-16 — done anyway, ahead of the deferred schedule, owner-confirmed.** Owner reports:
PostgreSQL manual backup tested, PITR (point-in-time recovery) enabled, a WAL archiving issue found
and resolved, and a point-in-time restore tested successfully. **Owner-confirmed, not independently
re-verified this session** — recorded at the same evidentiary standard as the 2026-08-11 Railway
config confirmation (§12 changelog), not as something checked directly against Railway. R-4 closed
in §7; release criterion 3 restored as met in §10. This was deliberately deferred, not required yet —
the owner chose to close it early rather than wait for Railway Pro, which is fine; nothing about
this resequencing's reasoning is invalidated, the gate just closed sooner than scheduled.

**P1 — current, unchanged in priority:**
1. **Bug-hunt findings closure** — still the highest priority, unaffected by this resequencing (§4).
2. **Load testing baseline — ✅ Complete, 2026-08-14.** Ran at three tiers — 100, 500, 1,000
   concurrent users — against a local docker-compose stack. Result: clean at 100 users, a measured
   capacity bottleneck by 500 (HikariCP's 10-connection pool exhausts; 4.4% error rate, 13–15s p95),
   worse at 1,000. Full findings: [`load-testing-baseline-2026-08-14.md`](../../investigations/performance/load-testing-baseline-2026-08-14.md);
   R-11 updated accordingly. **This did not just produce a number — it changed the risk's status**
   from "capacity unknown" to "capacity bottleneck identified, location known, fix not yet chosen."
   The item below exists because of that.
3. **Investigate measured bottleneck — ✅ Complete, 2026-08-14.** Five sub-questions answered with
   evidence (code review, `EXPLAIN ANALYZE`, real HikariCP hold-time metrics, and a direct pool-size
   experiment): no slow query, no missing index; every authenticated request pays ~5 fixed connection
   checkouts (auth filter chain); import holds connections 9–20× longer than any read endpoint;
   **raising `maximumPoolSize` to 20 or 30 was tested directly and made the error rate worse, not
   better** (4.4% → 41.7% → 13.2%), confirming this is CPU contention, not a connections shortage.
   Full findings: [`hikaricp-bottleneck-investigation-2026-08-14.md`](../../investigations/performance/hikaricp-bottleneck-investigation-2026-08-14.md).
   **No fix chosen** — the doc lays out the option space (auth-overhead caching, narrowing the broad
   transactions, import's per-row chatter) with tradeoffs; which to pursue, in what order, is still
   the owner's call. **Railway's actual Postgres connection ceiling — checked 2026-08-14:
   `max_connections = 500`** (via `railway connect postgres`, `SHOW max_connections;` against
   Production). Generous headroom over the pool of 10 in use today; Railway was never the wall the
   investigation found — that was local CPU contention, unrelated to this number. Closes the one
   open item this investigation left; does not change its finding that a bigger pool alone made
   things worse in the configurations tested.
4. **Remediation candidates — scoped 2026-08-14, deliberately not started.** Four levers, each with
   impact/risk/effort assessed:

   | Candidate | Impact | Risk | Effort |
   |---|---|---|---|
   | Accounts N+1 fix (`bankRepository.findById` per account → batch) | Low–Medium | Very low — isolated, mechanical | Small |
   | Dashboard transaction narrowing (fetch → close connection → aggregate in Java) | Medium — dashboard is 40% of traffic | Low–Medium — Hibernate lazy-loading care needed | Small–Medium |
   | Auth-overhead caching (the ~5 fixed connection checkouts every request pays) | Highest breadth — every endpoint | Medium — cache invalidation on role/permission change is security-sensitive | Medium |
   | Import transaction redesign (R2 call outside the transaction, reduce per-row chatter, reconsider synchronous reconciliation) | Highest single-operation impact | **Highest** — same code area as BH-001/003/004/005/006, real financial-correctness defects | Large |

   **Owner's sequencing decision: wait behind Phase 4.** Matches this plan's own §8 standing rule —
   Phase 4 (56 open bug-hunt findings) is serial with everything after it, and this remediation work
   was scoped as a diagnostic, not committed engineering time. None of the four candidates are
   started. **When the import transaction redesign does start, it gets the same correctness bar as
   the original BH-* fixes** — mutation-checked regression tests, real-Postgres verification, a test
   that fails against the old code and passes against the new — because it touches the exact code
   area that produced real balance-corruption and double-count defects before.

**P2 — after Railway Pro is purchased:**
1. Backup + restore drill, retention policy, recovery runbook (R-4, release criterion 3).
2. Production monitoring hardening — alerts, uptime checks, database metrics, resource thresholds,
   against final infrastructure rather than provisional.
3. Caching implementation (if the P1 measurement work justifies it) — Redis only after slow endpoints
   and expensive queries are identified and index optimization is exhausted first.

**Post-launch:** cache layer refinement, advanced performance work, future architecture
(Elasticsearch, event-driven scale-out, sharding — all already deferred per the architecture audit).

```
Now
│
├── Bug hunt closure
├── Import reliability completion
├── Load testing baseline ✅ (caching measurement folded in)
├── Security review
│
↓
Investigate measured bottleneck ✅
│
↓
Re-test baseline
│
↓
Railway Pro
│
├── Backup/restore verification
├── Production monitoring hardening
├── Scaling configuration
│
↓
Post-launch optimization
│
├── Cache layer (if justified by measurement)
├── Advanced performance work
└── Future architecture
```

**Status as of 2026-08-14:**

| Item | Status |
|---|---|
| Load testing baseline | Complete |
| Capacity bottleneck identified | Complete |
| Root-cause investigation | Complete — see [`hikaricp-bottleneck-investigation-2026-08-14.md`](../../investigations/performance/hikaricp-bottleneck-investigation-2026-08-14.md) |
| Remediation | Pending — 4 candidates scoped (impact/risk/effort), deliberately not started; sequenced behind Phase 4 per §8 |
| Re-test | Pending |

This does not change §9's dates — Block E (production readiness) is re-scoped, not shortened, since
the load-test effort was already estimated there and the restore-drill effort simply moves out of the
v1.0 window rather than disappearing.

---

## 6. Dependencies

| This | needs | because |
|---|---|---|
| Beta | P0 + P1 closed | A beta on a balance-corrupting import produces distrust that no later fix repairs |
| Load test | Multi-instance decision (D-4) | Testing one instance tells you nothing about the shape you will run |
| Multi-instance | Redis (rate limiter + import concurrency limiter are both in-memory) | `BH-034`: two single-instance controls in a design that is moving to multiple instances |
| Statement-byte deletion (BH-017) | Retention policy (D-3) | You cannot write the job before someone says how long "kept" is |
| PDFBox upgrade (M2 item 8) | The corpus gate | Upgrading the parser without regression coverage is the exact move the corpus exists to prevent |
| M3 implementation | The ground-truth model | ADR-005 §10, and it is right |
| Mobile release | Physical-device QA | Phone-auth and change-password OTP **cannot run in the simulator at all** |

---

## 7. Risk register

| ID | Risk | Sev | Prob | Impact | Mitigation | Status |
|---|---|---|---|---|---|---|
| R-1 | ~~Balance-corruption defects (BH-003/004/005) reach the first real user~~ | — | — | — | **Closed (Round 1, PR #63).** Demonstrated broken, demonstrated fixed, mutation-checked. No real user was ever exposed (D-1) | ✅ Closed |
| R-2 | ~~Cancelled imports resurrect (BH-001)~~ | — | — | — | **Closed (Round 1, PR #63).** Same evidence standard as R-1 | ✅ Closed |
| R-16 | Store enrolment has a wrong-turn cost | Medium | Realized once, mitigated | Apple's Organization flow demands a DUNS number the owner does not have; the Individual flow, already decided (D-9), needs none. Caught before submitting any org paperwork | Apple enrolment resubmitted correctly as Individual, per Apple's own docs confirming later conversion to Organization remains possible via account migration or app transfer | 🟡 Mitigated, awaiting Apple confirmation |
| R-17 | The plan file itself has been silently reverted twice by the shared-directory parallel-session pattern | Medium | Realized twice | Hours of PM analysis lost from disk each time, recoverable only because this session's own transcript retained it | Commit this file after every substantive update rather than leaving it as uncommitted working-tree state — see the note at the top of this document | 🟠 Active, mitigation in progress |
| R-3 | ~~Live deployment has not passed any release gate~~ | — | — | — | **Closed 2026-08-09 by D-1**: no real users, no customer data in production. Reopens the day the first external user signs up | ✅ Closed |
| R-3a | The rehearsal window closes silently | Medium | High | Restore drill, load test and destructive testing get cheap *only* while prod is empty; that advantage is lost without anyone deciding to lose it | Do Phase 5's drills against real production before beta, not after | 🟠 New |
| R-4 | ~~No backup or restore has ever been drilled~~ | — | — | — | **Closed 2026-08-16, owner-confirmed, ahead of the deferred schedule** — see §5a. Manual backup tested, PITR enabled, a WAL archiving issue found and resolved, point-in-time restore tested successfully | ✅ Closed |
| R-5 | Uploads are unscanned for malware | **High** | Medium | Attacker-supplied files reach PDFBox, which is ~2 years behind with no CVE scan | ClamAV sidecar or provider scan + PDFBox upgrade (M2 item 8) | 🔴 Open |
| R-6 | Single self-hosted macOS runner is a CI SPOF | Medium | Medium | All merges stop; migrated off hosted runners after a billing block | Document the rebuild path (done); keep a hosted fallback profile | 🟡 Partial |
| R-7 | Scope drift into M3 while Phase 4 is open | **High** | **Materialising now** | The launch date moves with no decision having been made | This plan; §8; the PM role | 🟠 Active |
| R-8 | Single-instance controls block horizontal scaling | Medium | High at load | Rate limiting silently degrades on a second replica | Redis, gated on D-4 | 🔴 Open |
| R-9 | Mobile has never run on a physical device, and is now **on the critical path** | **High** | Certain | Phone verification gates *every* protected endpoint; if APNs silent push or Play Integrity is misconfigured, nobody can sign up at all. Unknown unknowns with no prior art in this repo | D-2 chose v1.0. Mitigate by front-loading device bring-up to week 1 — discover in August, not October | 🔴 Open, **elevated** |
| R-9a | Store review is outside our control | Medium | Medium | A financial app draws more scrutiny; each rejection round-trip is 2–7 calendar days and cannot be worked around | Submit early with complete privacy/data-safety disclosures; budget one rejection in the Target date, two in Conservative | 🔴 Open |
| R-9b | No privacy policy or terms of service exist | **High** | Certain | Both stores refuse submission without them; this is a hard gate, not paperwork | Draft during Phase 4, not at submission time. Related to D-7 | 🔴 Open |
| R-13 | **Neither store account exists, and both gate the launch** | **Critical** | Certain | Apple's tail is 2–7 weeks and blocks iOS device bring-up entirely (APNs needs a paid account). Google's 12-tester/14-day clock cannot start until an installable build exists. Together they, not the code, set the date | **Enrol today.** Prove M2 on Android first, since a dev build needs no Play Console. Upload the closed test by 2026-09-12 | 🔴 **Open — top of the list** |
| R-14 | The tester streak resets | Medium | **Medium–High** (D-9 made this certain to apply) | 12 testers must stay opted in *continuously*; one drop-off can restart 14 days, straight off the Target date | Recruit 15–16 to hold 12; start the clock as early as a build allows, not when the app is polished | 🔴 Open, **now unavoidable** |
| R-15 | The privacy policy names an individual as data controller | Medium | Certain under D-9 | Your name and contact details are published on both store listings, for a product that ingests bank statements. Obligations under DPDP attach to you personally rather than to an entity | D-12: get a professional view during Phase 4, before the policy is published. A dedicated support address and a post-box rather than a home address | 🔴 Open |
| R-10 | Statement bytes retained indefinitely | High | Certain once R2 is configured | Bank statements held forever against a documented 48-hour promise | D-3 then implement | 🔴 Open |
| R-11 | Performance untested at scale (8 services load full history) | **High** *(raised 2026-08-14, was Medium)* | Certain, now measured | The single-instance, 10-connection HikariCP pool starts failing before 500 concurrent users (4.4% error rate, 13–15s p95) — see [`load-testing-baseline-2026-08-14.md`](../../investigations/performance/load-testing-baseline-2026-08-14.md). Not yet pinned to an exact number between 100 (clean) and 500 (failing) | Baseline done locally per §5a. Next: intermediate tiers to pin the exact ceiling; re-run against Railway once Railway Pro lands | 🟡 Measured, not yet fixed |
| R-12 | Single-contributor capacity | Medium | Certain | No redundancy; velocity is high but personal | Keep the doc discipline that already exists — it is the mitigation | 🟡 Accepted |
| R-18 | GitHub's deployment status on the current `main` tip (`d5aec3d`) reads "Deployment failed" (Railway) | Medium | Confirmed via GitHub API 2026-08-16 | Both `app.finoratech.info` and `api.finoratech.info/actuator/health` respond normally, so production is very likely serving the previous successful build, not the latest commit — not down, but stale | Check the Railway dashboard directly; this session has no access to diagnose further | 🔴 Open, new |
| R-19 | Real correctness bugs found in `DashboardService`/`InsightsService`'s date-anchoring for sparse/gappy transaction histories, discovered as a side effect of BH-042's review | Unknown — not yet scoped | Confirmed to exist, per PR #127's own scope note | Same defect class (BH-001/003/004/005/006) that produced this project's original P0 balance-corruption findings; currently has no bug-hunt ID and no owner | Give it a real BH-0XX ID and scope it, rather than leaving it as an unlabeled PR footnote | 🔴 Open, new |

---

## 8. Scope control — the active conflict

**The work in flight is not on the critical path.** `fix/wrapped-header-column-anchors`, ADR-005, the
ground-truth model and the corpus refinements are Milestone 3 groundwork. They are good, and the
ordering discipline behind them (contract before implementation) is exactly right.

They are also being built while **one Critical and twelve High defects sit unfixed in a deployed
system, three of which silently corrupt account balances.**

This is not an argument to stop that work permanently. It is the flag the PM role exists to raise:

> Before any further document-intelligence work, Phase 4 needs to start. If parser work continues
> in parallel, the v1.0 date moves out by roughly the duration of that work, because Phase 4 is
> serial with everything after it.

Recorded as R-7. The decision is the owner's; making it unconsciously is the failure mode.

---

## 8a. Fino — AI Financial Intelligence Layer (V2 discovery / product proposal)

**Status: parked. Post-v1.0. No implementation, no schema changes, no OpenAI integration, no new UI.**
The September 19 GA target is independent of this item.

Proposed direction, captured so the idea isn't lost while engineering capacity stays on GA:

- **What it is:** an AI assistant layer on top of Finora's existing financial data — not a generic
  chatbot. The model never queries PostgreSQL directly; it calls controlled backend tools
  (`get_monthly_spending`, `get_category_spending`, `get_net_worth`, `search_transactions`, etc.) and
  the backend returns exact figures for the model to explain.
- **Stack fit:** React + TypeScript → Spring Boot / Java 21 → PostgreSQL, using the official OpenAI
  Java SDK (Responses API, function calling) — integrated inside the existing backend, not a separate
  service.
- **Reuse, don't duplicate:** extract the analytical logic already in `DashboardService` into a shared
  `FinancialAnalyticsService` so Dashboard, Reports, and Fino compute from the same financial truth.
- **Rough milestone shape (not scheduled, not estimated):** basic chat loop → financial data read
  tools → comparative/trend analysis → proactive insights → controlled actions with explicit
  confirmation (budget creation, categorization). Actions are always confirm-before-execute; no
  personalized investment advice.
- **Explicitly deferred:** conversation-memory schema, tool definitions, UI design, and the "get that
  one loop working end-to-end first" build order all stay proposals until scoped as a real v2
  engineering plan after GA — informed by what production users actually use Finora for, rather than
  designed from assumptions now.

**Why parked:** the v1.0 date is already at-risk against 15 confirmed-open bug-hunt findings and 2
owner decisions; adding AI-dependency scope, schema changes, and a new UI surface to the release
candidate now would compound that risk for a feature not on the critical path to GA. See [[pm-role-for-finora]].

**Readiness contract:** `docs/roadmap/fino-v2-readiness.md` defines what v1.0 engineering may do now
without it being Fino work — opportunistic-only foundation (shared `FinancialAnalyticsService`,
clean service boundaries, consistent transaction typing, standardized analytics DTOs) done *only*
when a bug-hunt or release-gate fix is already touching that code, never as a scheduled task, never
moving §9's dates. Provenance, the event/audit trail, and admin analytics expansion are explicitly
deferred to V1.0.1 (post-GA, dedicated work). The acceptance test: every readiness item must be
justifiable as valuable even if Fino were cancelled.

---

## 9. Timeline

Single contributor at **~10 h/day**, which is the pace the velocity baseline was measured from. Mobile
is **in** scope (D-2). Effort figures are estimates; the completion percentages they build on are
measured.

### Web track

| Block | Work | Est. |
|---|---|---|
| A | P0: BH-001, 003, 004, 005, 006 + the tests none of them have | 4–5 d |
| B | P1: BH-002, 007, 011, 012, 013, 017, 019, 023, 026, 027 | 5–7 d |
| C | Security gaps: edge headers, masking guard, malware scan, retention job, PDFBox upgrade | 4–5 d |
| D | Test readiness: full E2E in CI, nightly job, cross-browser to green, the 7 named test gaps | 3–4 d |
| E | Production readiness: load test (with caching/index measurement folded in), runbooks, multi-instance decision, Dependabot batch. **Backup + restore drill moved to post-Railway-Pro — see §5a** | 3–4 d |
| | **Web subtotal** | **20–27 d** |

### Mobile track

| Block | Work | Est. | Note |
|---|---|---|---|
| M0 | **Store enrolment — Apple Developer Program and Google Play Console** | ~2 h of work, **2–7 weeks of waiting** | **Not started as of 2026-08-09.** Now the longest-lead item in the entire project — see §9a. Start today |
| M1 | EAS Build/Submit pipeline, signing credentials, first Android dev build | 2–3 d | Android needs no Play Console for a dev build, so this can start before enrolment clears |
| M2 | First physical-device bring-up: APNs silent push (iOS) and Play Integrity (Android) for Firebase phone auth | 3–5 d | **Highest-variance item in the plan.** Never done once. Phone verification gates every protected endpoint, so if this is wrong, nothing works |
| M3 | Native-surface QA: share sheet, system date picker, `expo-print` PDF output | 1–2 d | All three have only ever run against mocks |
| M4 | Mobile duplicate-review parity — `initialInclusion()` still silently unticks on `likelyDuplicate` with no review screen behind it | 2 d | The WI5 correctness rule, one platform over. Named in the M2 charter and owned by the mobile initiative |
| M5 | Mobile E2E (Detox or Maestro) + a CI slot | 3–4 d | None exists; the CI job bundles JS only |
| M6 | Store listings, screenshots, **privacy policy + ToS**, Play Data Safety, Apple App Privacy | 2–3 d | R-9b: the policies are a hard submission gate |
| | **Mobile subtotal** | **13–20 d** | |

### Calendar items — elapsed, not effort

| | | |
|---|---|---|
| **Apple enrolment** | **2 days – 7 weeks** | Individual: 24–48 h published, but 2–7 week waits with no communication were widely reported through early 2026. Organisation: 2–4 weeks plus D-U-N-S |
| **Google Play closed test** | **14+ calendar days** | 12 testers opted in *continuously*. See §9a — this is a hard clock, not an estimate |
| Store review | 5–10 calendar days | Includes one rejection round-trip. Overlaps beta |
| Beta soak | 7–10 calendar days | Real statements, a handful of invited users |

**Total: 33–47 working days of effort**, compressed by overlapping the two tracks and the calendar
items. At ~6 working days/week that is 6–8 weeks, and the mobile track's external gates set the floor.

> These figures are live in [`Finora-v1.0-Decisions.xlsx`](../../Finora-v1.0-Decisions.xlsx) — the
> Scope tab recomputes them from whatever you mark v1.0 / v1.1 / Cut.

**⚠️ Stale as of 2026-08-15 — built on the simultaneous-launch assumption D-11 reversed.** The table
below computes one combined date for both platforms; with iOS launching first (§5), the real
question is now "when does iOS launch" (gated by Apple enrolment, iOS device bring-up, D-7, D-12, and
App Store review) as a separate figure from "when does Android launch" (still gated by its own
12-tester/14-day clock, trailing iOS by however long that takes independently). **Deliberately not
recalculated yet** — D-7 has no defined pricing scope to estimate from, and re-deriving a real number
off an unscoped item would be inventing a date, not deriving one. Left below for its historical
basis, not as a current commitment.

| | Date | Basis |
|---|---|---|
| **Best case** | **2026-10-12** *(was 2026-09-28)* | Apple enrolment already started, device bring-up works first time, the Medium/Low sweep lands inside 8 days, no store rejection, 7-day soak |
| **Target** | **2026-11-06** *(was 2026-10-16)* | The estimate above with a normal discovery rate, one store rejection round-trip, and the Medium/Low sweep taking its full 10–12 days |
| **Conservative** | **2026-12-11** *(was 2026-11-27)* | Apple enrolment runs to the 6–7 week tail, *or* the Tier 3 performance work uncovers something structural, *or* the tester streak resets and the 14-day clock restarts |

**Why these moved: the owner pulled the S2/S3 backlog into v1.0 on 2026-08-09.** The Medium and Low
findings were scoped to v1.1 in §11's Scope table. Bringing them forward adds 10–12 working days
(estimate) of P2/P3 work ahead of a critical path that was already calendar-gated, and the days land
serially rather than in parallel — the same person cannot sweep `audit_logs` emission and bring up
an Android device at the same time.

**The decision was made with the impact stated in advance, and reaffirmed.** Recording that here
rather than as a surprise later: the cost is roughly three weeks, and it buys a materially cleaner
Medium/Low backlog at v1.0 instead of at v1.1.

**The 2026-09-12 closed-test milestone is now the binding risk.** It was comfortable at the previous
Target and it is not comfortable now. If the mobile track does not start until the sweep finishes,
that date is missed and the Target slips again — by more than the sweep itself costs, because the
14-day tester clock does not compress. Mobile bring-up should run *alongside* the sweep, not after it.

All three are **estimates, not confirmed dates.** Three unknowns dominate and none yields to planning:
how long Apple takes, the defect discovery rate during Phase 4, and whether Firebase phone auth works
on real hardware. The first is answerable this week by enrolling; the other two within two weeks of
starting. **The date should be re-confirmed once Apple enrolment clears** — that single event
collapses most of the spread between Best and Conservative.

**One dated milestone, and it is the only one in this plan:** the Google Play closed test must be
uploaded by **2026-09-12** for the Target date to hold. Everything else is an estimate; that one is
arithmetic.

**Why the mobile figure moved.** The 2026-08-09 baseline estimated mobile at 10–14 days and a target
of ~2026-10-10. It is now 13–19 days and 2026-10-16. Two items were missing rather than
underestimated: the mobile duplicate-review parity gap (M4) was recorded in the M2 charter and not
carried into this plan, and privacy policy / store compliance (M6, R-9b) was folded into "store
listings" when it is a hard gate of its own. D-1 pulled 1–2 days back in the other direction — no
balance-repair migration is needed for BH-003 — which is why the net is +6 days rather than +8.

---

## 9a. The store clocks — the real constraint on the date

**D-8 resolved 2026-08-09: neither store account exists yet.** Two externally-imposed clocks follow,
neither of which responds to effort, and together they now set the launch date more than the code
does.

### Google Play's closed-testing requirement

A **personal** Play Console account created after 13 November 2023 cannot ship to production until it
has run a closed test with **at least 12 testers opted in continuously for 14 days**. The rule was 20
testers until 11 December 2024, when Google reduced it to 12. **Organisation accounts are exempt.**

Three properties make this worse than it sounds, and all three are why it belongs in the plan rather
than in a checklist:

1. **The clock cannot start until an installable Android build exists.** It runs *after* M1 and M2,
   not beside them.
2. **Testers must actually open and use the app.** Adding 12 email addresses does not start the
   clock; dropping below 12 at any point can reset the streak entirely.
3. **Twelve real people is a logistics problem for a solo developer**, and it is the kind that looks
   trivial until day 9 when someone uninstalls.

Then production access is *applied for* and reviewed — Google states 7 days or less, occasionally
longer — on top of the 14. **Minimum calendar cost from first closed build to production access:
~3 weeks.**

**Mechanics worth getting right the first time**, since each of these costs a full restart:

- **It must be a *closed* test. Internal testing does not count.** This is the most common way the
  clock gets started and then found not to have started.
- Testers are real Google accounts on real Android devices, opted in via the closed-test link. The
  14 days must be *consecutive* — opting out and back in does not accumulate.
- **The build does not have to be final.** You can keep shipping updates to the closed track
  throughout. So the clock should start on the first build that installs and signs in, not on a
  polished one — this is the single cheapest schedule lever in the plan.
- **Do not use a paid "12 testers" service.** They exist, they are against Play policy, and account
  termination on a financial product with a launched iOS twin is not a recoverable position.

**iOS has no equivalent gate.** TestFlight imposes no tester minimum or waiting period, so under a
personal account the two platforms desynchronise by roughly three weeks. **D-11, resolved
2026-08-15: this desynchronisation is now the plan, not a risk to manage** — iOS launches first,
Android follows once its own gate clears.

### What that does to sequencing

**Corrected 2026-08-15.** The diagram and target date below describe **Android's own track only** —
before D-11, Android's clock set the single combined launch date; now it sets only Android's release
date, decoupled from iOS's.

```
enrol (done) → EAS + Android dev build (M1) → device bring-up (M2) → closed-test build uploaded
             → 12 testers × 14 continuous days → apply for production access → review → publish
```

Working backwards from a **2026-10-16** target, the closed test must be uploaded by roughly
**2026-09-12**. *(This target predates D-11/D-7's reversal and Phase 4's actual closure — kept for
its mechanics, not as a live date; see §9's stale-table note above.)*

> **Pre-D-11 framing, kept for the reasoning, not the conclusion:** "front-load mobile" was a
> requirement because sequencing mobile after web made the *one combined date* unreachable no matter
> how many hours were worked. **Post-D-11, that specific consequence no longer applies to iOS** — iOS
> isn't waiting on Android's tester-gate clock at all. The clock still fully applies to Android's own
> release, though, and the same logic still argues for starting Android device bring-up early: the
> longer it's deferred, the further Android trails iOS, and the closed-test build still has to exist
> before the 14-day clock can even start.

### Apple's enrolment tail

Individual enrolment is published at 24–48 hours, and through early 2026 developers widely reported
2–7 week waits with no communication. Organisation enrolment is 2–4 weeks plus a D-U-N-S number
(1–5 business days to issue, up to 2 more to reach Apple), and Apple stalls the enrolment on any
mismatch between the D-U-N-S record and the application.

**iOS device bring-up is genuinely blocked on this**, not merely inconvenienced: Firebase phone auth
on iOS uses silent APNs push, and the push entitlement requires a paid account. A free Apple ID gets
7-day on-device provisioning but no APNs — so the single highest-variance item in the plan (M2)
cannot be de-risked on iOS until enrolment clears.

**Android is the de-risking path.** An EAS development build installs on an Android device with no
Play Console at all, so M2 can be proven on Android in week 1 while Apple processes. Do that.

### The account-type fork — D-9, resolved

**Individual accounts on both stores** (2026-08-09), because no registered legal entity exists and
incorporating first would cost more time than the tester clock does.

| | **Individual — chosen** | Organisation |
|---|---|---|
| Apple enrolment | 24–48 h published (2–7 wk tail reported) | 2–4 wk + D-U-N-S |
| Google 12-tester gate | **Applies** | Exempt |
| Store listing shows | Your personal name | "Finora" |
| Needs a registered legal entity | No | Yes |

**What choosing individual commits us to.** Three things stop being optional:

1. **The 2026-09-12 closed-test milestone is now binding**, not conditional. **Updated 2026-08-15:
   it binds Android's own release, not the project's single launch date** — D-11 decoupled the two,
   so this is no longer "the only dated commitment in this plan" in the sense of gating everything;
   it gates Android specifically.
2. **D-10 and D-11 activate.** Twelve testers must be found. **D-11 resolved 2026-08-15: iOS first**
   — the "about three weeks apart" desynchronisation this row anticipated as a risk is now the actual
   plan, not something to decide.
3. **D-12 is raised.** With no entity, the privacy policy names *you* as the data controller and
   publishes your contact details on both store listings — for a product that ingests bank
   statements. **Partially resolved 2026-08-15: owner chose to hold for a lawyer's view** before
   deciding contact details; the answer itself is still open, and per §5 now sits directly on iOS's
   critical path rather than behind Android's tester-gate clock.

None of this argues against the choice. Given no entity today, individual is right: incorporating
first would put 2–4 weeks of paperwork ahead of every line of code, to save a 14-day clock that runs
in parallel with work you are doing anyway.

---

## 10. Release gates

| Gate | Status | What remains |
|---|---|---|
| **Development Complete** | ✅ | Core functionality is implemented across all three apps |
| **Feature Complete (v1.0)** | 🟡 | M2 items 7–8; password-policy convergence; async threshold decision (D-5) |
| **QA Complete** | 🔴 | **Corrected 2026-08-16 — "1 Critical + 12 High" was stale, left unedited since before the 08-14 defect-backlog correction (§4).** Current: 0 Critical, 0 High from the numbered bug-hunt backlog (P0–P3 all closed/accepted except BH-044's engineering, not yet built, and BH-045, deliberately descoped). The marker-row-pollution defect flagged earlier the same day was ticketed as BH-060 and closed same-day on re-verification — already fixed 08-11, see §4. What actually remains: full E2E still not in CI; cross-browser still never green; the 7 named test gaps below are unverified this pass (carried forward, not re-checked) |
| **Production Ready** | 🔴 | No load test, no malware scan, no edge security headers, no secret manager, single-instance controls, indefinite statement retention. **Backup/restore closed 2026-08-16, owner-confirmed, ahead of its deferred schedule — see §5a and R-4** |
| **Beta Ready** | 🔴 | Blocked on QA Complete. A beta on balance-corrupting imports is worse than no beta |
| **v1.0 Ready** | 🔴 | All of the above |
| **Go-Live** | 🔴 | Owner approval against this table |

**Explicit release criteria** — Finora is v1.0 when all of these hold:

1. Zero open Critical or High defects; every P0 fix carries a test that fails against the old code.
2. Full E2E green in CI, cross-browser green, smoke blocking on every PR.
3. ~~A restore from backup has been performed and timed, on a real database, at least once.~~
   **Met, 2026-08-16, owner-confirmed** — manual backup tested, PITR enabled, a WAL archiving issue
   found and resolved, point-in-time restore tested successfully. Done ahead of the post-Railway-Pro
   schedule this criterion was moved to on 2026-08-14 — see §5a.
4. A load-testing baseline at 100/500/1,000 concurrent users, recording API latency, database usage,
   import processing time, and memory usage at each tier — measuring current limits, not clearing a
   scale target (§5a). The same pass surfaces which endpoints/queries are slow, which is the
   caching-evaluation measurement folded into this criterion.
5. Uploads are scanned; PDFBox is current; the edge sets CSP and HSTS.
6. Statement retention matches what the product says it does, enforced by a job with a test.
7. A runbook exists for: stuck import job, dead-letter queue, failed deploy. **Database-restore
   runbook moves with the restore drill to post-Railway-Pro.**
8. 7+ days of beta with real statements and no P0/P1 raised.

---

## 11. Decisions required — owner input needed

| ID | Decision | Why it matters | Recommendation |
|---|---|---|---|
| ~~**D-1**~~ | ~~Is production serving real users?~~ | — | ✅ **Resolved 2026-08-09: no. Owner-only testing, no customer data.** Closes R-3; removes the need for a balance-repair migration; makes production a free rehearsal surface until the first real signup |
| ~~**D-2**~~ | ~~Is mobile in v1.0 or v1.1?~~ | — | ✅ **Resolved 2026-08-09: mobile is in v1.0.** Cost accepted: +13–19 working days, target moves 2026-09-19 → 2026-10-16. R-9 elevated; mobile joins the critical path |
| ~~**D-8**~~ | ~~Already enrolled in either store?~~ | — | ✅ **Resolved 2026-08-09: neither.** Enrolment is now the longest-lead item in the project (§9a). Conservative date moved 2026-11-13 → 2026-11-27 to absorb Apple's reported tail |
| ~~**D-9**~~ | ~~Individual or organisation store accounts?~~ | — | ✅ **Resolved 2026-08-09: individual, no legal entity exists.** Google's 12-tester gate now **applies**, which makes the 2026-09-12 milestone binding rather than conditional. D-10 and D-11 activate; D-12 is raised as a consequence |
| **D-10** | **Who are the 12 Play testers?** | **Live** (D-9 = individual). Twelve real people who install the app and stay opted in for 14 continuous days; the streak resets if the count drops | Line them up during Phase 4, not on the day the build is ready. Assume 15–16 recruited to hold 12 |
| ~~**D-11**~~ | ~~Simultaneous launch, or iOS first?~~ | — | ✅ **Decided 2026-08-15: iOS first**, reversing the standing "launch together" recommendation. TestFlight has no 12-tester gate, so iOS can go store-ready ~3 weeks before Android's Play closed-test streak completes. **Consequence, not yet reflected in §5/§9: those sections currently assume a simultaneous release** — the critical-path diagram, the "one coherent v1.0" framing, and any date math built on both tracks converging need re-deriving against this split. Flagged, not yet done |
| **D-12** | **Who is the named data controller in the privacy policy?** | Raised by D-9. Both stores require a published privacy policy naming who holds the data and how to reach them. With no legal entity that is **you, personally**, and the contact details are public on both listings. Finora ingests bank statements, so this is not a formality | **Owner's process decision, 2026-08-15: hold for a lawyer's view before deciding contact details.** The underlying question (support email + PO box vs. home address vs. something else) is **still open** — this decides *how* to decide it, not the answer itself. Still blocks privacy-policy publication until resolved |
| ~~**D-3**~~ | ~~Statement retention policy — how long are the bytes kept?~~ | — | ✅ **Resolved 2026-08-09: reference-counted sweep, ~90 days**, not an R2 lifecycle rule and not the 30-day placeholder this row used to suggest — a sweeper reclaims R2 objects no DB row references, rather than deleting on a clock regardless of live references. Implementation PR in flight. Alongside it, **BH-025 also resolved**: skip the Postgres BYTEA dual-write once an R2 object address exists, rather than keeping it and bounding it explicitly — the dual-write's own justification (BH-046 Phase 3/4) had already collapsed. Implementation PR in flight |
| **D-4** | **One instance or many at launch?** | Rate limiting and import concurrency are both in-memory; a second replica silently degrades both | **Load-test evidence gathered 2026-08-16, recommendation unchanged: one instance for beta, Redis before GA if projected load needs it.** A real 2-replica experiment (not inference from reading the code) found the degradation is not small: import concurrency's ceiling exactly doubled (6 → 12 concurrent, out of `app.import.max-concurrent`'s configured 6), login rate limiting rose 80% (10 → 18 allowed in one 30-request burst, against `RATE_LIMIT_LOGIN_MAX`'s configured 10). Full method and results: [`multi-replica-rate-limit-2026-08-16.md`](../../investigations/performance/multi-replica-rate-limit-2026-08-16.md). Owner decision on one-vs-many itself still open -- this closes the evidence-gathering half D-4 asked for, not the decision |
| ~~**D-5**~~ | ~~Async import: threshold or poll-interval?~~ | — | ✅ **Resolved, checked 2026-08-14: no threshold, immediate-poll-then-backoff** — exactly the recommendation. `POLL_SCHEDULE_MS = [100, 200, 400, 800, 1500]` (`ImportProgress.tsx`), directly tested: `ImportProgress.test.tsx` asserts the exact schedule and that the component follows it |
| ~~**D-6**~~ | ~~Password policy — does the backend enforce the complexity the frontend suggests?~~ | — | ✅ **Resolved, checked 2026-08-14: no drift to reconcile.** Backend enforces `@Size(min = 8, max = 72)` (`AuthDtos.java:31`); the frontend's strength meter is explicitly documented in its own comment as "a nudge for the user, never a submission gate" (`Register.tsx:20-23`) — both sides already agree on length-only, and the meter was never meant to be a stricter gate the backend forgot to match |
| ~~**D-7**~~ | ~~Pricing, subscription model, data-retention promises in the ToS~~ | — | ✅ **Decided 2026-08-15: IN SCOPE for v1.0**, reversing the standing "launch free, decide before v1.1" recommendation — pricing must be finalized before production launch. **Scoping pass done 2026-08-16** (see `docs/proposals/billing-subscription-entitlements-proposal.md`, committed same day after sitting uncommitted since 08-12): backend genuinely greenfield (no `Plan`/`Subscription`/`Entitlement`/`Payment`/`Referral`/`Wallet` entity, no gateway dependency, no tier field on `User` -- re-verified against current code, not just the proposal's own 08-12 claim), architecture designed (fail-closed entitlements, webhook idempotency, append-only subscription/wallet ledgers), Free/Plus/Premium taxonomy and entitlement mapping already Product-approved (08-12). **One concrete, unblocked gap found**: `frontend/src/pages/landing/plans.ts` still shows Free/Premium/Family/Future, not the approved Free/Plus/Premium taxonomy -- small (effort S), needs no further business decision, just hasn't been done. **Genuinely blocked on business decisions, not engineering**: actual price points, payment gateway (Razorpay is a disabled UI button only, zero SDK/code integration), upgrade/downgrade timing, refund policy, trial terms, and the real business address `Contact.tsx` still placeholders (blocks Razorpay's own activation review) |
| ~~**D-13**~~ | ~~Approve a live reproduction attempt against BH-006; accept the password-re-prompt UX cost?~~ | — | ✅ **Resolved twice.** Reproduction approved and fixed (PR #75). **The owner's approval of "double prompt is fine" was given on my inaccurate description** — PR #75 as shipped was an unconditional failure for every password-protected reimport, not a double-prompt. Found and corrected same night by a separate commit (`4133910`, direct to `main`, not via PR). Verified end-to-end against a real encrypted PDF |
| ~~**D-14**~~ | ~~Individual or Organization Apple enrolment, given the DUNS prompt?~~ | — | ✅ **Resolved: Individual**, consistent with D-9. The DUNS prompt meant the Organization flow had been entered by mistake; Individual needs no DUNS. Confirmed convertible to Organization later if a legal entity is ever registered |
| ~~**D-15**~~ | ~~Now that C5 (Gmail merchant parsing) is done, build C6 (Gmail Intelligence: connection UI, review UI, merchant dashboard, unknown-merchant learning, cross-source reconciliation, notifications, premium billing) next, or hold it for post-GA?~~ | — | ✅ **Decided 2026-08-15: hold C6 for post-GA, ship only C5.4 now.** C5.4 = the minimum to make C5 usable at all — a Gmail connection status screen and a review queue for staged Gmail transactions, reusing the existing CSV/PDF review UI/infra per C5-B's own design. Everything else in the original C6 proposal stays parked: C6.5 (notifications) and C6.7 (premium/billing) already exist as fully-scoped proposals independently sequenced post-GA (`notification-communication-platform-proposal.md`, `billing-subscription-entitlements-proposal.md`); C6.2 (merchant dashboard) likely extends the existing `AdminStatementAnalysisController.summary()` pattern rather than needing new architecture; C6.3 (unknown-merchant learning) and C6.4 (cross-source reconciliation) are confirmed genuinely new scope, held for post-GA; C6.6 (Google OAuth app verification) is a launch requirement once Gmail sync has real users, not a premium feature, and gets picked up under launch-readiness rather than C6. Sequence: **C5.4 → launch blockers (store enrolment, D-7 pricing, D-12 privacy policy, Phase 5) → GA → C6**, not C5.4 → C6 → launch. **Superseded by D-17** — the sequencing changes, the audit findings this decision recorded (which C6 pieces already have proposals, which are genuinely new) still stand and fed directly into D-17's Sprint 1 scoping. |
| ~~**D-16**~~ | ~~C5.4 is merged (PR #122) -- start building C6 now, or hold per D-15?~~ | — | ✅ **Decided 2026-08-15: design/architecture only, no implementation.** Owner's explicit framing: "Proceed with C6 planning and architecture documentation only. No implementation until GA blockers are closed. The purpose is to reduce post-launch execution time while preserving launch focus." D-15's sequencing stands unchanged -- this is scoping work product, not a reopening of the hold. See `docs/proposals/gmail-intelligence-platform-proposal.md` for the resulting design: C6.2 (merchant monitoring), C6.3 (unknown-merchant learning) and C6.4 (cross-source reconciliation) designed there; C6.5/C6.7 cross-referenced to their existing proposals; C6.6 reclassified as a launch requirement, not C6 scope. **Superseded by D-17.** |
| ~~**D-17**~~ | ~~Explicitly override D-15/D-16 -- begin C6 before GA, launch blockers continuing in parallel rather than gating C6?~~ | — | ✅ **Decided 2026-08-15: yes, explicit override, tradeoff named and accepted.** Owner's own framing: "This supersedes the previous D-15/D-16 sequencing decision. The tradeoff is accepted: additional feature development may increase GA timeline risk, but the team prioritizes completing the intelligence layer earlier to improve product differentiation." Sequence changes from **C5.4 → launch blockers → GA → C6** to **C5.4 → C6 (Sprint 1) with launch blockers continuing in parallel**. **Scope, not all of C6 at once** -- owner explicitly rejected starting all seven C6 sub-items together as "another long-running feature cycle." Sprint 1 = C6.1 (finish: sync-health/receipt-count visibility on the connection card, confidence + reasoning on the review queue) + C6.2 (Merchant Intelligence Dashboard: `merchant_extraction_metrics`-shaped success-rate analytics, admin-only). Explicitly deferred, not part of Sprint 1: C6.3 (unknown-merchant learning), C6.4 (reconciliation), C6.5 (notifications, already has its own proposal), C6.7 (premium/billing, already has its own proposal). C6.6 (Google OAuth app verification) stays a launch requirement per D-15, not C6 scope. Security boundary explicitly reaffirmed, unchanged by this decision: trusted-sender validation, sanitization, validation, review-before-ledger -- no parser bypasses review, no auto-created transactions. |
| ~~**D-18**~~ | ~~With Sprint 1 done, is "make Gmail Intelligence feel like a premium SaaS product" in scope now -- and if so, which piece?~~ | — | ✅ **Decided 2026-08-15: audit first, then the smallest high-trust piece only -- the Transaction Explanation panel (PR #129), nothing else from the pitch.** Owner pitched a seven-part "premium feel" roadmap (improved C6.3/C6.4/C6.5, an improved OAuth consent screen, tiered billing, plus four new bets: Transaction Explanation, Spending Memory, Financial Health Score 2.0, an AI Money Assistant), with their own priority order putting Transaction Explanation first as "small effort, huge trust." Audited before building, per this plan's own standing rule (§8a) -- most of the pitch's "good examples" already exist: `RecurringService` already computes upcoming-payment intelligence, `InsightsService` already produces spending-increase narratives with a named reason, `RefundNetting` already nets refunds off purchases, `DashboardService.computeHealthScore` already exists. Offered the owner four options via a direct question rather than assuming: build Transaction Explanation only, continue as a proper C6 Sprint 2 (C6.3+C6.4 as D-16 already scoped them), reopen the Personal CFO premium layer the 2026-08-14 decision explicitly parked pre-launch (billing tiers, AI assistant), or hold everything for launch blockers. **Owner chose Transaction Explanation only** -- a real, if small, feature (`GET /transactions/{id}/explanation`, surfacing `Transaction.decisionSource`/`decisionRuleId`, both already persisted and never previously exposed), explicitly not a reopening of the parked premium layer or of D-17's own Sprint-1-only scoping. Shipped same day: backend 2825/2825, frontend 447/447, tsc clean, browser-verified. |
| ~~**D-19**~~ | ~~Owner restructured the full C6 pitch into C6.1-C6.9 with a "surface existing intelligence first, build genuinely new intelligence second, monetize third" sequence -- start Step 1 (the surfacing work) now, before GA?~~ | — | ✅ **Decided 2026-08-15: yes, Step 1 only -- Financial Health Dashboard, Notification Center, Trust Center.** A second audit (following D-18's) checked the owner's full C6.1-C6.9 restructuring against every relevant proposal doc already in the repo, not just the codebase: found real, substantial overlap on four of the five "new" items. **C6.5** (Financial Copilot) -- `DashboardService.buildNotifications()` already computes budget/low-balance/card-due alerts, never rendered; `InsightsService` already produces spending-increase narratives with a named reason; `RecurringService` already predicts subscription due dates. Only cashflow-runway forecasting is genuinely unbuilt. **C6.6** (Trust Center) -- backend is further along than any doc describes: `GmailConnectionService.disconnect()` already revokes at Google and audits both connect/disconnect; `DeviceController` already does real per-device revoke. Missing only a unifying page. **C6.7** (Premium tiers) -- `billing-subscription-entitlements-proposal.md` already locks tier names (Free/Plus/Premium, a real 2026-08-12 product decision) and a full `Plan`/`Subscription`/`Entitlement` schema with fail-closed `hasEntitlement()`; none of it built (`FeatureFlag` today fails **open**, unsafe as a paywall). Owner's ₹199/₹499 price points are new; the architecture around them isn't. **C6.9** (Health Score) -- code is *more* advanced than the pitch's own framing: `DashboardService.computeHealthScore` already returns a weighted 5-component breakdown (Savings Rate/Debt Utilization/Emergency Fund/Spend Consistency/Cash Flow Stability), sent to the frontend, never rendered -- the pitch's "Savings/Debt/Spending/Investments" 4-category framing doesn't match what already exists and needs reconciling before any UI gets built for either. **C6.8** (Financial Memory) confirmed the one genuinely novel item -- no durable per-user baseline or whole-month anomaly detection exists anywhere. Owner's own conclusion from this: "Finora's backend maturity is ahead of its UI/product maturity... the highest ROI now is surfacing existing intelligence, not adding more hidden backend systems" -- and proposed a three-step sequence (surface existing → build genuinely new → monetize). **Step 1, greenlit now, UI-only over already-computed backend data, no new intelligence, no billing, no AI:** (1) Financial Health Dashboard -- render `DashboardSummaryDto.healthScore`/`healthLabel`/`healthBreakdown`; (2) Notification Center -- render `DashboardService.buildNotifications()`'s existing output; (3) Trust Center -- one Settings page surfacing `GmailConnectionStatusDto` + `DeviceSession` data, both already exposed separately. **Explicitly held, not part of Step 1:** C6.3 (merchant learning), C6.4 (reconciliation), C6.5's cashflow forecasting, C6.8 (financial memory), C6.7 (billing) -- Step 2 ("build genuinely new intelligence") and Step 3 ("monetize") stay undecided. One correction the owner's own message needed: their Step 1 originally listed "Transaction explanation panel" as outstanding -- it isn't, D-18/PR #129 already shipped it same day. |
| ~~**D-20**~~ | ~~D-19 Step 1 shipped complete. Which Step 2 item ("build genuinely new intelligence") starts first -- C6.3 (merchant learning), C6.4 (reconciliation), C6.5's cashflow forecasting, or C6.8 (financial memory)?~~ | — | ✅ **Decided 2026-08-16: C6.4, staging-time direction only.** Audited all four candidates against existing code and docs before asking, same discipline as D-18/D-19. **C6.3** and **C6.4** both already have real designs from D-16 (`gmail-intelligence-platform-proposal.md`) -- neither needs fresh design work, only building. **C6.4** is the more bounded of the two: the design doc's own §3 recommends its "staging-time" direction first (match a staged Gmail receipt against confirmed bank transactions -- amount + date window + merchant-name normalization -- before it's ever confirmed into the ledger), over a larger "post-confirm/ledger-wide" direction that would need a new cross-transaction "Merge" operation neither `DuplicateDetector` nor `ReconciliationService` has today. **C6.3**'s own design admits a real gap: the admin backlog/approval-queue shape is designed, but the actual template-synthesis logic (turning one sample email into a `MerchantTemplate`'s extraction pattern) "doesn't exist anywhere and has to be designed as its own piece of work" -- larger and less ready than C6.4. **C6.8** (Financial Memory) turned out substantially closer to already-existing than D-19's own audit implied: read `InsightsService.java` in full and found it already computes per-category "this month vs. up-to-3-prior-months average" deviation at a ≥15% threshold, already producing natural-language sentences, already rendered on Dashboard's AI Insights section. The real gap is narrower than the original C6.8 pitch -- whole-month (not just per-category) anomaly detection, and possibly a richer trailing-months UI. **C6.5's cashflow forecasting** confirmed genuinely novel: `grep -rn "forecast\|runway\|projected.*balance\|balance.*fall"` across `backend/src/main/java` returned three matches, all inspected and confirmed false positives (unrelated CSV/PDF balance-column-fallback doc comments) -- zero projection/runway logic exists anywhere, and no design for it exists either. **Owner chose C6.4's staging-time direction.** Scope: a new fuzzy-matching signal (amount near-match + date within a window + merchant-name normalization, e.g. "amazon.in" vs "AMZN MKTPLACE") wired into `GmailStagingBridge.stage()`, which today hardcodes `likelyDuplicate=false`/`duplicateMatch=null` and never calls `DuplicateDetector` at all. No new UI -- `DuplicateReview.tsx` already renders `StagedRow.duplicateMatch`/`likelyDuplicate`, built for the CSV/PDF path, and reads the same fields regardless of source. **Explicitly not in scope:** C6.4's post-confirm/ledger-wide direction (the "Merge" operation), C6.3 (merchant learning), C6.5's forecasting, C6.8's whole-month anomaly extension -- all stay held, unscoped, for a future Step 2 item. |

---

## 12. How this plan stays current

On any report from an engineering session, review, deployment or bug hunt:

1. Mark completed work in §2 and §3.
2. Add newly discovered work to §4 at its real priority.
3. Recalculate §2's weighted completion.
4. Re-check §6 dependencies and §5 critical path.
5. Reassess §7 risks; escalate anything that moves a date.
6. Re-derive §9; **if a date moved, say why it moved** in the changelog below.
7. Name the next highest-priority action.

### Plan changelog

| Date | Change | Why |
|---|---|---|
| 2026-08-16 | **D-7 (pricing/billing) scoped, engineering side only. `billing-subscription-entitlements-proposal.md` committed** — it existed, thorough and complete, since 2026-08-12 but was never committed, the same load-bearing-uncommitted-file risk this plan has now flagged three times. Re-verified its core claims against current code rather than trusting the 4-day-old doc forward: backend genuinely greenfield confirmed (no Plan/Subscription/Entitlement/Payment/Referral/Wallet entity, no gateway dependency anywhere, no tier field on `User`), `FeatureFlag`'s fail-open default confirmed (the exact footgun the proposal's Correction #3 warns entitlements must not inherit). **One concrete, unblocked gap found**: `plans.ts` still shows Free/Premium/Family/Future, not Product's own already-approved Free/Plus/Premium taxonomy (08-12 decision) -- small, needs no further business input, just hasn't been done yet. Everything else genuinely needs the owner's own decisions (§10 of the proposal: price points, gateway, upgrade timing, refund policy, trial terms) before more engineering can proceed. See §11's D-7 row for the full breakdown | Same discipline as every other scoping pass this session -- verify a standing claim against current code before building on it, rather than trusting a doc's own age-appropriate but now-stale-by-default framing |
| 2026-08-16 | **D-4 evidence gathered: a real 2-replica load test, not inference from reading the code.** Owner moved to launch-readiness work after BH-060/C6.5; picked D-4 as the one launch blocker that's pure engineering investigation rather than the owner's own action (store enrolment, testers, legal). Extended `scripts/load-test/` with a new nginx-fronted, scaled-`backend` docker-compose override and a focused k6 burst script, and measured `RateLimiter`/`ImportConcurrencyLimiter` at 1 vs. 2 replicas: import concurrency's ceiling exactly doubled (6 → 12), login rate limiting rose 80% (10 → 18). Both classes' own doc comments already predicted this qualitatively; this is the first real number behind it. Recommendation unchanged from D-4's own standing text (one instance for beta, Redis before GA), now evidence-backed rather than asserted. Also surfaced, not fixed, a separate real bug: two genuinely concurrent identical-content uploads from one user 500 instead of resolving cleanly, a race on `idx_import_sessions_live_content` -- flagged as a follow-up, out of scope for this investigation. Full method/results/artifacts: [`multi-replica-rate-limit-2026-08-16.md`](../../investigations/performance/multi-replica-rate-limit-2026-08-16.md) | D-4 explicitly asked for load-test evidence before its "one instance for beta" recommendation could be trusted rather than asserted -- this closes that gap the same way BH-060 closed its own evidence gap, by measuring instead of inferring |
| 2026-08-16 | **BH-044 confirmed fully closed — engineering was already built, this section's own "not yet built" was stale and contradicted §1's already-correct note.** Asked to "start BH-044," investigated first rather than assuming a green field: `V89__audit_log_redaction.sql`, `AuditService.scheduledRedaction()`/`redactExpiredMetadata()`, and full test coverage (`AuditServiceTest`, `AuditServiceRedactionIT`) all exist, shipped as PR #121 (`52072e3`). Independently re-verified — migration file present, `@Scheduled` method present, tests present, commit real and on `main` — not just re-quoted from a claim. Built as in-place redaction on `audit_logs` itself, not the separate `audit_payloads` table originally described as the owner's proposal; that design change was never flagged as a decision anywhere in this plan until now. §1, §3, and §4 corrected. **New, undecided gap found in the process:** `Bank` entities' audit events store their real ID inside the `metadata` field redaction wipes, permanently breaking the admin Bank audit lookup for redacted rows — flagged in the code's own doc comment, never surfaced here. Also corrected: §1's bug-hunt summary still described BH-042/043's follow-up PRs as "open, unmerged" after they'd already been recorded fully closed elsewhere in this same file. **With this correction, the "remaining bug-hunt findings closure" item on the notification-proposal safety gate has no known open numbered defect left** — only two unticketed, undecided gaps (R-19, the Bank-audit-lookup gap), neither yet assigned a BH-0XX ID or a fix/accept decision. Sentry/production monitoring remains the one fully open item | Same "verify before acting on a claim" discipline as the BH-060 and C-8 Track B corrections earlier today — a section of this same file saying "not yet built" was itself the unverified claim this time, not an external doc |
| 2026-08-16 | **C6.5 shipped, scoped to existing-intelligence surfacing only, merged as PR #139.** Per the owner's own correctness-first reprioritization, audited what C6.5 actually still needed before building anything: the Financial Health Dashboard and Notification Center were already done in D-19 Step 1, so the only genuine gap was `RecurringService.detectForUser`'s subscription/recurring-payment predictions (merchant, cadence, average amount, projected next charge) -- computed and exposed via `GET /api/v1/recurring` since before this session, with a frontend API client already scaffolded, but never rendered anywhere except a per-transaction badge on Ledger/Reports. Added a read-only "Subscriptions & Recurring Payments" card on Dashboard.tsx, same pattern as Financial Health Score/AI Insights -- no new backend logic. Worded "expected", never "due", since `nextEstimate` is a projection from historical cadence, not a confirmed bill date. Browser-verified live: seeded 3 real monthly Netflix transactions, confirmed the pattern was detected and rendered correctly ("netflix · Monthly · ₹649 · expected in 8 days"). Frontend 452/452, all 8 PR checks green. **This closes out the owner's stated BH-060 → C6.5 → launch-blockers sequence's middle step** -- C6.3, C6.5's forecasting, and C6.8's whole-month extension all remain held | Fourth time this session "audit before building" found the real gap was narrower than the original ask -- consistent with D-18/D-19/D-20's own findings, worth continuing to check for rather than assuming a clean slate |
| 2026-08-16 | **Correction: "C-8 Track B completion" was never a real remaining gate item — it closed 2026-08-11, five days before this file's own 2026-08-16 entry (above) listed it as one. New §4a added to fold the whole C-8/parser-reliability workstream into the living plan, separated from actual launch blockers, specifically so this class of mistake stops happening.** The root mistake: Track B (the marker-row-pollution fix) was conflated with "all of C-8" — they're not the same thing. C-8 is the broader OCR-routing/evidence-engine effort; Track B was one closed sub-part of it. Re-verified fresh against git rather than trusting the 2026-08-12 memory snapshot this depended on, and found it was itself stale in the *good* direction: **P-003 (ICICI duplicate columns), P-004 (PNB reverse chronology), and P-006 (SBI extraction failure) are all also closed** (`f9ea543`, `282fe5b`+`42543cd`, `89de6b9` respectively) — none previously checked off anywhere. Only **P-005** (trace redactor destroys textual dates) remains genuinely open in the Parser Reliability Milestone; Track A #2/non-ledger #3/C-8.3/R2 remain genuinely open in the C-8 evidence model, all blocked on real-corpus acquisition, not engineering. See §4a for the full table. **None of this changes any v1.0 release gate** — C-8/parser work was never one. It does correct the notification proposal's own kickoff checklist, which named "C-8 Track B closed" as one of four items gating implementation: that box is now genuinely checked, same as backup/recovery | Same root cause as the BH-060 mistake two entries below: a status label repeated across turns without being re-checked against the actual current state. The fix here is structural, not just a one-time correction — §4a exists so the next re-baseline has something to check against instead of nothing |
| 2026-08-16 | **R-4 (backup/restore) closed, owner-confirmed, ahead of its deferred schedule.** Owner reports: PostgreSQL manual backup tested, PITR (point-in-time recovery) enabled, a WAL archiving issue found and resolved, and a point-in-time restore tested successfully. Recorded at the same evidentiary standard as the 2026-08-11 Railway config confirmation — owner-confirmed directly, not independently re-verified against Railway this session. §5a, §7 (R-4), and §10 (release criterion 3) all updated. This item was deliberately deferred to post-Railway-Pro on 2026-08-14 (§5a) as a sequencing decision, not a scope cut; the owner chose to close it early rather than wait, which the original resequencing reasoning always allowed for. **Remaining safety-gate items, unchanged: C-8 Track B completion, remaining bug-hunt findings closure, Sentry/production monitoring readiness.** Notification-platform implementation stays frozen behind the full gate, not just this one item | Owner-reported infrastructure work, recorded the same way every other owner-confirmed production fact in this file is — distinct from something checked directly, per this plan's own standing discipline (see the 2026-08-11 entries below) |
| 2026-08-16 | **BH-060 closed same day it was ticketed: the marker-row financial-correctness defect it describes was already fixed 5 days earlier.** Before starting any fix work per the owner's correctness-first reprioritization (below), re-verified the ticket against current `main` rather than trusting its own investigation doc. Found `ade05ca` (2026-08-11, "stop statement marker rows from becoming ledger transactions") already adds the exact `RowKind.BALANCE_MARKER` exclusion the ticket says is missing, across both PDF and CSV staging -- confirmed empirically, not just by reading the diff: `PdfPreviewGeneratorTest`'s golden-fixture assertion was already changed from `hasSize(6)` to `hasSize(4)` in that commit, and all 94 tests `ade05ca` touched pass on current `main`. The ticket's own investigation had `ade05ca` in its git history when written and still asserted the pre-fix behavior -- no clean explanation found, but the test evidence is unambiguous. Also checked the ticket's own defense-in-depth concern (`persistSection()` has no marker check of its own) and confirmed it's a non-issue: both real confirm entry points run `ConfirmedRowIntegrity.requireSameRows()` -- built for the unrelated BH-006/BH-023 bug -- before `persistSection()` ever runs, and a marker row is never in the server-side truth set that check compares against. Closed [issue #138](https://github.com/siddharth705/finora/issues/138) with full evidence; §4 and §10 corrected to match | Same "verify before acting on a claim" discipline this plan has applied to production/config claims before -- a ticket citing real file paths and line numbers reads as credible, but credibility isn't verification, and duplicating an already-shipped fix would have wasted the exact correctness-first priority the owner had just set |
| 2026-08-16 | **Owner reprioritized: BH-060 (marker-row financial-correctness defect) fixed before any further C6 work.** Presented with C6.4's completion and three options (continue C6 intelligence, fix correctness first, or return to launch blockers), owner chose correctness-first: "A wrong ₹50,000 transaction is worse than a missing AI feature." Stated sequence: BH-060 fix → C6.5 limited to surfacing already-existing intelligence (notification center, health score UI, subscription reminders) → launch blockers → C6.3/C6.8/C6.7 held for post-GA. This session moves to BH-060 next | Same discipline as every other reprioritization in this file (D-7, D-11, D-17): a real, reasoned scope decision gets recorded the moment it's made, not silently absorbed into whatever gets built next |
| 2026-08-16 | **D-20/C6.4 shipped: staging-time cross-source reconciliation, merged as PR #137.** `GmailReconciliationMatcher` adds the fuzzy-matching signal the design doc named as missing -- exact amount, a 3-day date window, and merchant-name similarity (normalized Levenshtein distance over `CategoryRules.extractMerchant`'s reduction) -- wired into `GmailStagingBridge.stage()`, which previously hardcoded `likelyDuplicate=false`/`duplicateMatch=null` and never called `DuplicateDetector` at all. Reported through the existing `DuplicateMatch`/`StagedRow` shape with a new `"LIKELY"` confidence tier alongside CSV/PDF's `"EXACT"` -- `DuplicateReview.tsx` needed zero code changes, confirmed live: a synthetic `amazon.in` receipt correctly matched a real `AMZN MKTPLACE 4521` bank transaction, the exact abbreviation case the design doc names. Scoped to the staging-time direction only, per D-20 and the design doc's own recommendation -- post-confirm/ledger-wide reconciliation (needs a new cross-transaction "Merge" operation neither `DuplicateDetector` nor `ReconciliationService` has) stays out of scope. Backend 2845/2845 (incl. a real-Postgres IT test for the new JPQL query), frontend 448/448, all 8 PR checks green including Strix. **C6.3, C6.5's forecasting, and C6.8's whole-month extension remain held** -- no further Step 2 item decided | Closing the loop D-20 opened, same discipline as every other decision-to-shipped record in this file |
| 2026-08-16 | **Full PM status report, re-baselined against `origin/main` @ `a2f2cad` (post D-20/PR #136). No new completion percentage asserted — still 81% — held for a new reason this pass, not the same one as the last two holds.** §10's Release Gates table corrected: "1 Critical + 12 High open" had gone stale since before the 2026-08-14 defect-backlog correction and was never touched by any of the six re-baselines since, silently contradicting §1/§4's own already-current "0 Critical" numbers the whole time. **BH-042 and BH-043 confirmed fully closed** — their follow-up PRs (#132 `ffa6ad3`, #133 `a77cca7`) merged 2026-08-16 04:02/04:06 UTC, before D-19/D-20 were even recorded, but the "open, unmerged" status in §4 was never updated to match. **New, more severe than R-19: a live financial-correctness defect** — `OPENING BALANCE`/`CLOSING BALANCE` marker rows with a real date import as a real EXPENSE transaction equal to the account's balance, on both PDF and CSV paths, found via an uncommitted investigation note, no BH-0XX ticket yet. Three ready PRs sitting idle: **#130** (Phase C data export, all 8 checks green, Strix clean, zero review comments, open since 08-15) and **#97** (BH-008/009/010 regression coverage, green, open since 08-14) have nothing blocking merge but the merge itself; **#102** (CSP fix) has a failing Backend CI check whose log matches this plan's own documented self-hosted-runner infra-flake signature (line ~232) rather than the CSP diff itself — inference, not confirmed, since the run hasn't been retried since 08-14. **Structural gap, not a defect: the C-8/ADR-006 evidence-engine workstream (Track A/B, several open investigation docs) has zero references anywhere in this file** — it's tracked entirely outside the living plan, in `docs/architecture/adr/adr-006-evidence-decision-reconciliation.md` and a set of uncommitted investigation docs. **Also uncommitted despite being load-bearing**: `Finora-v1.0-Decisions.xlsx` (§9a already cites it as the live source for the v1.0/v1.1/Cut split), `docs/project-management/capability-audit-2026-08-14.md` (the source document D-18/D-19/D-20 all lean on by name), and two product-roadmap docs — the exact uncommitted-shared-checkout failure mode this file has already recorded losing work to twice (see the 2026-08-10 and 2026-08-14 `ad13f30` entries below) | Requested as a full PM status update. Net effect on the headline number: BH-042/043 fully closing argues up, the new marker-row defect and the C-8 tracking gap argue down — held at 81% rather than move either direction on a mix of good and bad news that doesn't net to a re-derived figure, consistent with this file's own rule that a number gets earned by review, not moved by vibes. **Next highest-priority actions, per §12 rule 7:** (1) merge #130 and #97 — zero-risk, already-done work; (2) ticket and fix the marker-row-pollution defect — real production data-integrity bug, highest-severity open item found this pass; (3) commit the four uncommitted load-bearing files before this checkout's state is lost a third time |
| 2026-08-16 | **D-20 recorded: Step 2 scoped and greenlit -- C6.4's staging-time reconciliation direction only.** All four Step 2 candidates (C6.3, C6.4, C6.5's forecasting, C6.8) audited against existing code and docs before asking, per this plan's own standing discipline. C6.3 and C6.4 both already have real designs from D-16; C6.4 is the more bounded of the two (the design doc's own recommendation) and was chosen. C6.8 turned out substantially closer to already-existing than D-19's own audit implied -- `InsightsService` already does close to what the pitch asked for. C6.5's forecasting confirmed genuinely novel via a targeted grep across the backend with every match manually inspected and ruled a false positive. See §11's D-20 row for the full breakdown | Same "audit before vision" discipline as D-18/D-19, now applied a third time to the same C6 restructuring -- each pass has found real existing capability the original pitch didn't credit, which is worth continuing to check for rather than assuming the backlog is a blank slate |
| 2026-08-16 | **Local `main` and `origin/main` had diverged (9 unpushed local commits, 31 unpulled origin commits, including this session's own D-19 Step 1) -- found while starting D-20's work, reconciled same session.** Investigated before merging, per this plan's own standing rule for unexpected repo state: checked for real Flyway migration collisions (none -- V80-V88 identical on both sides) and inspected both merge conflicts by hand. Neither was a real conflict -- `User.java` and this plan doc both showed origin/main as a strict superset of local main's one relevant edit each (origin already had the full Phase B account-deletion fields; origin's plan doc already showed BH-044 merged, superseding local's stale "PR pending" note). Merged, resolved by taking origin's side on both files, verified backend (2848 tests, 0 failures/errors) and frontend (448/448) both green post-merge, then pushed. Local main's 9 real fixes (Kotak/PNB import corrections, a migration renumber, a BH-044 doc note) are preserved, not lost | Parallel Claude Code sessions on this repo have caused real Flyway migration-version collisions before; treating a diverged branch as "unexpected state" worth investigating rather than force-pushing over it is the same discipline this plan already applies to uncommitted work and stale branches generally |
| 2026-08-16 | **D-19 Step 1 complete: all three items shipped, one of them needed no new work at all.** (1) Financial Health Score (PR #134) — a card on Dashboard.tsx rendering `DashboardService.computeHealthScore`'s 5-component breakdown, which has been computed and sent to the frontend since before this session and never rendered. (2) Notification Center — audited before building and found already fully built: `TopBar.tsx` already has a working bell icon reading `DashboardService.buildNotifications()`'s output, with an unread-count badge, per-account read-state tracking, and a "Mark all as read" action. No PR needed. (3) Trust Center permissions (PR #135) — `GmailConnectionStatusDto.grantedScopes` has existed since C5.4, never rendered; Connected Apps and Active Sessions already share one Settings page (the "unify onto one page" half of the original ask was already true), so the actual gap was narrower than scoped: a "Permissions" line translating the real granted OAuth scopes into plain English, deliberately saying what Google's consent screen granted ("Read Gmail messages") rather than what Finora's own trusted-sender policy restricts it to ("Read receipts only" would have overclaimed). Backend 2832/2832 unaffected (only PR #135 touched no backend code; PR #134 also had zero backend changes), frontend 448/448 across both PRs, tsc clean on all touched packages, both browser-verified against real seeded data. **No Step 2 decided yet** — C6.3/C6.4/C6.5's forecasting, C6.8 (financial memory), C6.7 (billing) all stay held per D-19's own scoping | Closing the loop D-19 opened, same discipline as every other Sprint/Step completion in this file — and the Notification Center finding is itself worth recording: the second and third "audit before vision" passes this session (D-18, D-19) both confirmed the same pattern the 2026-08-14 decision first found, that a meaningful fraction of "new work to build" already exists and just needs discovering, not building |
| 2026-08-15 | **D-19 recorded: greenlit Step 1 of a restructured C6.1-C6.9 roadmap -- Financial Health Dashboard, Notification Center, Trust Center. Step 2 (genuinely new intelligence) and Step 3 (monetization) stay undecided.** Owner restructured the original C6 pitch into a coherent platform (C6.1-C6.9) with an explicit "surface existing intelligence → build genuinely new intelligence → monetize" sequence, and their own conclusion that "Finora's backend maturity is ahead of its UI/product maturity." A second audit against every relevant proposal doc in the repo (not just the codebase) confirmed it: `DashboardService.buildNotifications()` and `.computeHealthScore()` are both already fully computed and never rendered; `GmailConnectionService`/`DeviceController` already implement everything a Trust Center needs; `billing-subscription-entitlements-proposal.md` already locks a Free/Plus/Premium tier design more detailed than the new pitch. Only C6.8 (financial memory/anomaly baseline) and C6.5's cashflow-runway forecasting are genuinely unbuilt. Step 1 is deliberately the safe half of the restructuring: three UI-only surfaces over data that already exists, no new intelligence, no billing, no AI | Same discipline as D-17/D-18: a real, reasoned scope decision that extends past D-17/D-18's own stated boundary (Sprint 1 = C6.1+C6.2 only; D-18 = Transaction Explanation only, explicitly not a Sprint 2) gets its own named decision and its own tradeoff statement, not a silent expansion |
| 2026-08-16 | **Re-baseline against `origin/main` @ `d5aec3d` (134 commits since the last-reflected baseline, `cc17716`). No new completion percentage asserted — still 81% — for the same reason the previous re-baseline gave: more unreviewed feature surface has landed underneath it.** Four parallel research passes plus direct production checks, rather than re-quoting this file's own last entry forward. **New since `cc17716`:** D-17 (owner-recorded, quoted: "This supersedes the previous D-15/D-16 sequencing decision... the team prioritizes completing the intelligence layer earlier to improve product differentiation") authorized C6.1+C6.2 (PRs #124/#128, merged); D-18 (owner-recorded, but recorded *after* PR #129/#131 had already merged — the one process gap found this pass, since D-17 itself followed "update the plan first" correctly) authorized the Transaction Explanation panel; the account-lifecycle trilogy — Phase A (#115) and Phase B (#123) merged, **Phase C (#130, data export) open, CI green, Strix clean, awaiting merge**; BH-042 partially closed and BH-043 core-closed, both with a genuine follow-up gap still open (#132, #133 — see §4's 2026-08-16 update); BH-044's redaction engineering (PR #121) appears to have shipped, not independently re-verified this pass. **None of this is reflected in §2's per-workstream weighting.** §1, §4, §7 (new R-18, R-19) updated directly; §2/§9 deliberately left untouched — recalculating either honestly needs the same kind of review this entry is flagging as overdue, not a guess. **The central finding: effort velocity and calendar-gated progress have now diverged about as sharply as they can.** ~20 PRs merged and two owner-approved features shipped in roughly 36 hours, against **zero confirmed movement** on any of the five items that actually gate a launch date (Apple enrolment, Google Play enrolment, D-7 pricing/ToS scope, D-10 named testers, D-12 privacy-policy contact) — checked directly against the repo, not inferred. §9's Best/Target/Conservative dates stay marked stale, for the same reason already recorded: D-7 has no scope yet to derive a number from, and inventing one would be exactly the kind of asserted-not-derived figure this file's own opening line disclaims. **New, not previously flagged: GitHub's deployment status on the current tip reads "Deployment failed" (Railway)** — both `app.finoratech.info` and the backend health endpoint respond normally, so production is very likely serving a previous build, not this one; needs the owner's own Railway access, see R-18. **Also new: real, currently unticketed correctness bugs** in `DashboardService`/`InsightsService`'s date-anchoring for sparse histories, found as a side effect of BH-042's review (R-19) | Requested as a full PM status update ("what is next?"), explicitly re-baselined from `origin/main` rather than this file's own stale snapshot, per §12's own procedure. The point of distinguishing effort velocity from calendar-gated progress is that this project's history (§9a) already proved the store clocks set the date, not the code — this re-baseline is the sharpest evidence yet that the gap between the two is widening, not closing |
| 2026-08-15 | **8-angle review of PR #124/#128/#129 (D-17 Sprint 1 + the D-18 Transaction Explanation panel) found and fixed 2 real correctness bugs plus 5 smaller issues, merged as PR #131.** Bugs: a REVOKED Gmail connection was invisible to `GET /status` (`GmailConnection.Status.LIVE` deliberately excludes REVOKED, but the status endpoint used the same `findLiveConnection` query sync uses -- so C6.1's own "Needs reconnect" UI could never fire against real REVOKED data; fixed with a new `findCurrentConnection` method scoped to the status endpoint only). A BETWEEN category rule's "Why this category?" explanation leaked its raw internal storage format (`amount is between "1000,5000"` instead of `1000 and 5000`). Also closed the design gap behind the REVOKED bug: `GmailConnectionStatusDto` now computes and sends `needsReconnect` itself, so `Settings.tsx` no longer re-derives that classification from raw string literals with no compile-time link to the backend enum. Smaller fixes: `GmailReviewService` now routes through the same canonical `categorySource` → `DecisionSource` mapping `ImportService` uses at confirm time, instead of a hand-rolled string check that could silently drift from it; a wasted category-lookup query removed from `TransactionExplanationService`; admin-portal's `formatWhen()` (defined identically three times) collapsed into one shared helper; an accessibility gap between two near-duplicate modals in `Ledger.tsx` closed. Backend 2832/2832, frontend 443/443, admin-portal 309/309, all green; the REVOKED fix specifically browser-verified by seeding a real REVOKED row and confirming the UI now shows it correctly | Requested as a standing "quick bug fix and gap check" -- an 8-angle finder pass (correctness, removed-behavior, cross-file, reuse, simplification, efficiency, altitude, conventions) rather than a single read-through, on the reasoning that a shipped feature's own tests passing doesn't mean a systematic pass across it won't find something the original build missed, which it did here |
| 2026-08-15 | **D-18 recorded: audited a seven-part "premium SaaS feel" pitch for C6, built only the smallest piece (Transaction Explanation panel, PR #129).** Owner pitched improved C6.3/C6.4/C6.5, an improved OAuth consent screen, tiered billing, and four new bets (Transaction Explanation, Spending Memory, Financial Health Score 2.0, an AI Money Assistant) -- their own priority order put Transaction Explanation first as "small effort, huge trust." Audited before building (§8a): most of the pitch's own good examples already exist in this codebase (`RecurringService` for upcoming-payment intelligence, `InsightsService` for spending-increase narratives, `RefundNetting`, `DashboardService.computeHealthScore`). Presented the owner four real options rather than assuming one; **owner chose Transaction Explanation only** -- explicitly not a C6 Sprint 2, not a reopening of the Personal CFO premium layer the 2026-08-14 decision parked pre-launch, and not a departure from D-17's own Sprint-1-only scoping. Built as `GET /transactions/{id}/explanation`, surfacing `Transaction.decisionSource`/`decisionRuleId` -- both already persisted at categorization time, never previously exposed to any client. Backend 2825/2825, frontend 447/447, tsc clean, browser-verified against a real stack (a manually-set category and a defaulted one) | Same "audit before vision" discipline this plan applies every time premium/CFO scope comes up (see the 2026-08-14 parking decision's own note that roughly half of previously-proposed premium features already existed) -- and the same "record the decision, don't silently expand or silently hold" pattern as every other D-item in this file |
| 2026-08-15 | **C6 Sprint 1 (D-17) complete: both C6.1 and C6.2 merged same day.** C6.1 (PR #124) added the two things C5.4 shipped without: a `reasoning` line on each review-queue item, honest that category defaults to "Other" because no merchant-category engine exists yet (C6.3, still held), and a distinct "Needs reconnect" connection-card state for `REAUTH_REQUIRED`/`REVOKED` — previously indistinguishable from "never connected," which silently dropped the user's account context. C6.2 (PR #128) added a Gmail-parser-health section to admin-portal's existing Merchant Intelligence page: success rate per merchant domain, worst-first, over the last 30 days. **Correction to D-17's own note, found while building it:** the entry below describes a new `merchant_extraction_metrics` table; that turned out unnecessary — `gmail_processed_messages` already holds one row per message forever (C4's own idempotency design), so a `GROUP BY` query over the existing table does the job, the same pattern `StatementAnalysisSessionRepository#failureCodeLayoutCounts` already established. No new table, no new admin-portal page (extended the existing one). Both PRs: full backend suite green (2813/2813), all CI checks passed, browser-verified against a real local stack before merge. **No Sprint 2 decided yet** — C6.3/C6.4/C6.5/C6.7 stay held per D-17's own scoping | Closing the loop D-17 opened: the plan said Sprint 1 would ship C6.1 + C6.2 specifically, not all of C6, so recording that it did — and recording the table-design correction the same way this file already corrects D-15's own wrong guess about `AdminStatementAnalysisController`, rather than quietly building around it |
| 2026-08-15 | **D-17 recorded: explicit override of D-15/D-16 — C6 begins now, launch blockers continue in parallel rather than gating it.** Owner's own framing: "This supersedes the previous D-15/D-16 sequencing decision. The tradeoff is accepted: additional feature development may increase GA timeline risk, but the team prioritizes completing the intelligence layer earlier to improve product differentiation." Sequence changes from **C5.4 → launch blockers → GA → C6** to **C5.4 → C6 (Sprint 1) with launch blockers continuing in parallel**. Scope is deliberately narrow, not all seven C6 sub-items at once — owner explicitly rejected that as "another long-running feature cycle." **Sprint 1 = C6.1 completion** (sync-health/receipt-count visibility on the connection card; confidence % + detected category + reasoning on the review queue, alongside Approve/Edit/Reject) **+ C6.2** (Merchant Intelligence Dashboard: admin-only success-rate analytics backed by a new `merchant_extraction_metrics`-shaped table — processed/successful/failed/average-confidence/last-failure per merchant). No AI extraction, no learning engine, no billing in Sprint 1. **Explicitly deferred, unchanged from D-16's audit:** C6.3 (unknown-merchant learning), C6.4 (reconciliation), C6.5/C6.7 (already have their own proposals). C6.6 (Google OAuth app verification) stays a launch requirement per D-15, not C6 scope. **Security boundary explicitly reaffirmed, unchanged by this decision:** trusted-sender validation, sanitization, validation, review-before-ledger — no parser bypasses review, no auto-created transactions. §3's Gmail Sync row, §5's critical path, and §11's D-15/D-16 rows all updated to reflect the override | Owner's own explicit instruction: "if you choose this, update the project plan first. Otherwise the team will have conflicting instructions (D-15 says stop, this says continue)." A named, reasoned override with the tradeoff stated up front — recorded the same way D-7/D-11 reversed earlier decisions in this same file, not silently overwritten |
| 2026-08-15 | **Re-baseline: this file is materially behind `origin/main`, and its own §1/§2 numbers (81%, baselined against `cc17716`) predate a large, unweighted block of new engineering.** Since `cc17716`, `main` gained: a full Gmail statement-import integration (OAuth connection PR #104, single-use-grant seam test #106, access-token refresh/dead-grant detection C1 #107, scope verification C2 #108, trusted-sender gate C3 #110, message discovery + scheduled worker C4 #113 — five sequential phases, all merged), reversible encryption at rest for third-party credentials (ADR-007, PR #100), a 5-state user-facing import status model replacing the dead `StatementImport.status` field (#109), a live-failure-banner UX distinction (#114), and a real same-day incident (`fix/duplicate-v81-migration` #111 — a duplicate `V81` Flyway migration broke `main`'s ability to start; fixed same day, confirmed by the next green run). Two more PRs are open and unreviewed: account self-service deactivation/reactivation (#115) and a Gmail merchant-parser framework (#116). **None of this is reflected in §1's 81% or §2's per-workstream weighting** — it is new, unquantified surface area (a new OAuth trust boundary, a new encrypted-credential store, a new scheduled background worker, a new migration-numbering near-miss), and per this plan's own rule, newly discovered unreviewed surface area lowers completion rather than leaving it unstated. **No new completion percentage is asserted here** — recomputing §2 honestly requires actually reviewing the Gmail integration's security posture (OAuth scope handling, token storage, the trusted-sender gate's bypass surface) the way the 08-11 audit did for the rest of the codebase, not guessing a number. That review has not happened yet and is the next concrete action, not a formality. CI status at the true current tip (`22ffac7`) is **pending** at the time of this note — the two most recent commits on `main` have not yet returned a confirmed green run; the last confirmed-green commit is `c19a46d` (#113, 2026-08-15 04:11 UTC) | Requested re-baseline explicitly required starting from `origin/main`, not the file's own stale snapshot. Found real, unreviewed scope growth mid-plan rather than a quiet update — recording that honestly (as a completion-lowering unknown, not a guessed number) is the point of this rule existing |
| 2026-08-15 | **§5 critical path restructured for D-11 (iOS-first); §9/§9a flagged stale, dates deliberately not recalculated.** Owner's explicit instruction: fix the dependency model, don't invent dates ahead of D-7's pricing scope. §5's diagram split into three tracks (web/iOS/Android) instead of web/mobile-combined; iOS no longer waits behind Android's 12-tester/14-day clock. **Two new blockers now sit on iOS's path that didn't before today:** D-7 (pricing/ToS, unscoped) and D-12 (privacy-policy contact, held for legal review) — both used to be able to hide behind Android's ~3-week clock; decoupled, they're both directly on the critical path to first launch now. §9's Best/Target/Conservative table marked stale (built on the old combined-date assumption) but left in place for its historical basis. §9a's sequencing narrative corrected to describe Android's own track, not the whole project's date; its "front-load mobile" reasoning kept, its "target date becomes unreachable" conclusion no longer applies to iOS specifically. No new dates estimated anywhere | The owner was explicit: recalculate the *structure* first, and don't estimate D-7/D-11 dates until pricing scope and legal review exist to derive them from — inventing a number here would be exactly the kind of asserted-not-derived figure this plan's own opening line disclaims |
| 2026-08-15 | **C5.4 merged (PR #122) — Gmail connection UI + review queue, closing out the Gmail Sync workstream (C1–C5.4, PRs #104–#122).** D-16 recorded: asked to "start C6" once C5.4 shipped, reversing D-15 on its face; owner's actual intent, once clarified, was narrower — produce C6's design/architecture as a document, not start implementation. D-15's hold stands. Two real CI failures fixed en route to merging #122, both caught before merge, neither shipped: a synthetic test fixture email pairing a real-sounding name with a real provider domain tripped the whole-tree PII ratchet, and `GoogleOAuthController` called two repositories directly, bypassing the service layer (`LayerDependencyDirectionTest`) — moved into `GmailReviewService`. `docs/proposals/gmail-intelligence-platform-proposal.md` written as this decision's deliverable | Recording the near-miss (a scope decision that looked reversed until clarified) the same way this plan records everything else worth knowing later — and because the fix pattern (CI catching a controller/repository layering violation, a PII fixture leak) is itself evidence the project's own guardrails work as designed |
| 2026-08-15 | **C5 complete (all six merchant parsers — Amazon, Ola, Uber, Zomato, Myntra, Booking.com — PRs #116/#118/#119, all merged, CI green, Strix clean); D-15 recorded: hold C6 (Gmail Intelligence: connection/review UI, merchant dashboard, unknown-merchant learning, cross-source reconciliation, notifications, premium billing) for post-GA, ship only C5.4 (bare user-facing completion: connection status screen + review queue, reusing existing CSV/PDF review infra) now.** Audited the proposed C6 phase against existing docs before accepting it as new scope, per this plan's own standing rule (§8a's "audit before vision"): C6.5 and C6.7 already exist as independently-scoped proposals (`notification-communication-platform-proposal.md`, `billing-subscription-entitlements-proposal.md`), both already sequenced post-GA on their own; C6.2 likely extends the existing `AdminStatementAnalysisController.summary()` pattern rather than needing new architecture; C6.3 (unknown-merchant learning) and C6.4 (cross-source reconciliation) confirmed genuinely new, held for post-GA; C6.6 (Google OAuth app verification) reclassified as a launch requirement, not a premium feature. Phases table (§3) gets a new Gmail Sync row, mirroring how M3 already runs outside the main phase sequence | Same pattern as §8's Fino parking and D-7/D-11/D-12: a real scope-timing decision, made consciously with the tradeoff named, rather than drifting into more feature work while store enrolment, pricing and privacy policy sit untouched (R-7). Recording it now, before C5.4 starts, so the sequence (C5.4 → launch blockers → GA → C6) is what gets built against, not re-litigated later |
| 2026-08-15 | **Four owner decisions recorded: D-7, D-11, D-12 (partial), BH-044.** Asked in one batch after the remaining-open-items check. **D-7 reversed**: pricing/ToS moves IN scope for v1.0, must be finalized before production launch — new unscoped work, not yet estimated. **D-11 reversed**: iOS launches first, not simultaneous with Android — §5/§9 still assume simultaneous and need re-deriving against this, flagged but not yet done. **D-12 partially resolved**: owner chose to hold for legal review before deciding contact details; the underlying question (what to publish) stays open. **BH-044 retention direction decided**: redact — keep the audit event, drop financial metadata after a defined window (owner's proposed default: 730 days), rather than truncate or keep forever; the actual retention framework and redaction job remain unbuilt, unscoped work. §4 and §11 updated | Recording decisions the moment they're made, not paraphrasing them from memory later — same discipline this plan applies to everything else. D-7 and D-11 both add real, currently-unscoped critical-path risk; naming that now, rather than only when §5/§9 are next touched, keeps the plan honest about what "decided" does and doesn't mean here |
| 2026-08-14 | **§11 corrected: D-5 and D-6 were already resolved in code, never marked so.** Checking the six still-live owner-decision items the same way D-13/BH-006 was checked found two already answered: D-5 (async import poll strategy) — `POLL_SCHEDULE_MS = [100, 200, 400, 800, 1500]`, immediate-then-backoff, exactly the recommendation, directly tested. D-6 (password-policy drift) — no drift exists; the frontend strength meter's own comment states it was "never a submission gate," and both sides already agree on the backend's 8-char minimum. Both struck through in §11; §2 workstream 1's remainder text corrected to drop "password-policy convergence" as outstanding. D-4, D-7, D-10, D-11, D-12 confirmed still genuinely open — real logistics/compliance/business calls, not code questions. No date change | Same failure mode as the P2/P3 bug-hunt drift found earlier today, just in §11 instead of §4 — a decision already answered by the code but still listed as awaiting the owner wastes their attention on a non-decision. Checked against the code and its tests rather than assumed resolved from the recommendation text alone |
| 2026-08-14 | **Phase 4 status corrected; completion recomputed; a pre-existing arithmetic error found and fixed.** Checked the remaining P3/Low bucket the same way as P2 (previous entry): all 18 findings closed or accepted, none open. That makes the entire P0–P3 bug-hunt backlog closed/accepted except `BH-044` (owner decision) and `BH-042`/`043`/`045` (parallel session) — §3's Phase 4 row updated from the stale "0%, 56 open" baseline to reflect that. §2's workstream 4 (Security & privacy) updated 88%→90% for the three newly-confirmed closures (`BH-037`, `BH-039`, `BH-046`). Recomputing §2's own Total from its row contributions gave **80.6%, not the stated 81.6%** — a pre-existing 1pp error, unrelated to today's changes. Corrected total: **80.8% → 81%**, and §1's headline (previously 83%, which never matched §2's 82% either) now matches §2 exactly. Net effect: the headline number goes *down* despite real additional progress, because it was never actually supported by the table under it | Completion figures exist to be checked, not quoted forward — this plan's own opening line claims "every number... is derived from the repository, not asserted." An unverified total silently drifting from its own row sum is the same failure mode as every other drift this plan has caught and corrected today, just in the numbers instead of the prose. Recorded rather than quietly rounded away |
| 2026-08-14 | **Plan-drift correction: six P2 findings (`BH-014`, `BH-029`, `BH-032`, `BH-036`, `BH-037`, `BH-046`) were already fixed in code and never recorded closed.** Checking "what's the next open bug-hunt item" against current code, not the stale 08-09 report, found each already fixed by a prior session — `LoginExistenceOracleIT`, `ImportJobSourceFormatIT`, `ProductionConfigValidatorTest`, `CorrelationIdCorsContractTest`, `ImportServiceStorageDualWriteTest` all green. `BH-044` is half closed (growth-rate fixed; retention explicitly blocked on an owner decision, not engineering). §1 and §4 updated; P2 now has no actionable engineering item left. No date change | The same discipline this plan already applies to line-number drift (BH-007) and status drift (BH-048) applies to closure drift too — a finding fixed in code but recorded as open is exactly as misleading as the reverse, and it was only caught by re-deriving status from the code and its tests rather than re-quoting the 08-09 report forward |
| 2026-08-14 | **BH-039 coverage completed — the `ImportSessionRepository` half of the cross-tenant guard.** PR #96's regression test's surviving reference was a `StatementImport` row, so the sweep's `existsByObjectKey(...) || existsByObjectKey(...)` guard's first clause alone kept the object alive and the `ImportSessionRepository` half was never actually exercised. Added `sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveImportSession` (surviving reference is another tenant's still-staged `ImportSession`), mutation-checked the same way as the original, committed directly to `main` (`a5365dd`, no PR). No date change | A regression test that passes for the wrong reason (short-circuited by the other half of an OR) is the same failure mode BH-039 itself is about — closing "regression coverage added" without checking which branch it actually reaches would have left the exact gap it claimed to close |
| 2026-08-14 | **BH-039 closed CLOSED–VERIFIED.** PR #96 merged (`8abfe074`). No live defect, confirmed against real generated SQL; missing cross-tenant regression coverage now in place. Full backend suite green with no recurrence of the `AcquisitionWiringIT` flake. No date change | Closes the day's sixth and last bug-hunt item (BH-048, BH-007, BH-053, BH-018, BH-058, BH-039) at the same VERIFIED bar throughout |
| 2026-08-14 | **BH-039 investigated: no live defect, regression coverage added.** The finding's own "becomes a cross-tenant defect the moment the sweep is built" trigger already occurred (BH-017's sweep exists), but checked directly against the real generated SQL that its reference counting is already global, not per-tenant — the warned-about trap was avoided. Added the missing cross-tenant regression test plus explicit warning comments naming the exact future mistake that would reintroduce it, in [PR #96](https://github.com/siddharth705/finora/pull/96) (not yet merged). No date change | A "Low/Potential Risk" finding whose trigger condition occurred deserves the same re-verification discipline as anything else — confirming a warned-about defect did NOT materialize is itself real work worth recording, not something to silently assume from the finding's age |
| 2026-08-14 | **BH-058 class swept and closed — last open P2 test-infrastructure item.** PR #95 merged (`2c40ffa9`): one real occurrence found (`ImportJobStoreIT`'s recovery tests asserting exact counts on table-wide `recoverAbandoned()`), fixed with the noise-fixture technique the original BH-058 fix established; systematic sweep of the rest of the suite found nothing else. Required an admin-override merge after `Backend (Java 25)` failed 4 times on `AcquisitionWiringIT` — confirmed beforehand as a pre-existing runner-level flake (same failure hit `main` itself on unrelated docs-only commits the same day), not a code defect; the underlying cause was fixed independently and merged immediately after (`ba33253`), confirming the diagnosis. No date change | Closes an item that had been carried as "flagged, not swept" since the original finding across three separate closure reports. The admin-override is recorded per this plan's own standing practice — state what was bypassed and why, with the evidence that justified it, not just that it happened |
| 2026-08-14 | **BH-018 closed CLOSED–VERIFIED — both halves now closed.** PR #93 merged (`02d9d287`): the remaining memory-materialization half (`file.getBytes()` holding up to 10 MB on the heap per upload). Turned out to be interface-wide (`StatementStorage.store(byte[])`, not just one call site) but scoped additively — a new streaming overload as the one real per-backend implementation, the two callers that already hold content in memory for parsing reasons left untouched. Regression test needed its own correction mid-flight: tracking read-chunk sizes didn't actually prove the property (`readAllBytes()` also chunks its reads), tracking write-chunk sizes did. No date change | Closes the last of today's four P1/P2 bug-hunt fixes (BH-048, BH-007, BH-053, BH-018) with the same VERIFIED bar throughout — demonstrated broken, then demonstrated fixed, not inferred from a green suite alone |
| 2026-08-14 | **BH-053 closed CLOSED–VERIFIED.** PR #92 merged (`c8bc96a`): the check-then-act race in `MerchantLearningService.confirm()`, precisely self-documented by the class's own comments since it was written (including a pre-emptive warning against the tempting wrong fix), closed with a native atomic upsert that stays inside the caller's transaction. The existing regression test only proved the race existed; rewritten against real Postgres to prove it's closed, mutation-checked against the pre-fix code. BH-018's remaining half (memory materialization on upload) is in PR #93, not yet merged — turned out to be an interface-wide question, not one call site, scoped additively rather than as a full conversion. No date change | Same pattern as BH-048/BH-007 earlier today: close a finding the moment it's actually verified, not at the next scheduled re-baseline, and record status precisely (in-review vs. merged-and-verified are not the same thing) |
| 2026-08-14 | **Remediation candidates scoped, deliberately not started.** Four levers from the bottleneck investigation — accounts N+1 fix, dashboard transaction narrowing, auth-overhead caching, import transaction redesign — each assessed for impact/risk/effort in §5a item 4. Owner's sequencing decision: wait behind Phase 4 (56 open bug-hunt findings), per this plan's own §8 rule that Phase 4 is serial with everything after it. Owner's correctness decision: when the import transaction redesign does start, it gets the same bar as the original BH-* fixes (mutation-checked regression tests, real-Postgres verification) since it touches the exact code area that produced BH-001/003/004/005/006. No date change — this is scoping, not scheduled work | Following the standard set right after the investigation itself: don't let a diagnostic's momentum turn into unscoped engineering work. Named the sequencing conflict (this plan's own §8 rule) explicitly rather than silently starting remediation, and got an explicit owner decision on both what order and what rigor, matching how every other cross-cutting decision in this plan has been recorded |
| 2026-08-14 | **Railway Production Postgres connection ceiling checked: `max_connections = 500`.** Closes the one open item the HikariCP bottleneck investigation left unresolved. Checked directly against the real production database (`railway connect postgres`, `SHOW max_connections;`), not estimated — Railway CLI installed and authenticated this session, `psql` installed via `libpq` since neither was present locally. Result folded into both the plan (§5a item 3) and the investigation doc (§8): 500 is far above the pool of 10 in use today, so Railway was never the constraint the investigation's pool-size experiment ran into — that was local CPU contention. Does not change the investigation's core finding (raising the pool made things worse in the configurations tested); a larger pool remains available to try later, but only after the CPU-bound issues (auth overhead, broad transactions) are addressed, not before. No date change | Owner's follow-up request after the investigation flagged this as the one thing it couldn't answer locally. Production access was gated behind an explicit approval (the auto-mode classifier blocked the first attempt at a direct `psql` connection to production; the owner approved the specific read-only query before it ran) rather than proceeding automatically, consistent with treating production infrastructure access as requiring confirmation |
| 2026-08-14 | **A real, tested commit briefly went missing from `main`, caught before it was lost for good.** `1c5b1e4` — `fix(imports): don't 500 the sessions list when a multi-account session is staged`, regression-tested, full suite green — was made by a parallel session in this same shared working directory, then dropped: a `git reset` (not run by this session) moved `main`'s tip back one commit immediately before this session's own HikariCP-investigation commit landed on top of the reset-to commit, and a later `pull --rebase --autostash` from elsewhere locked that state in and pushed it, with `1c5b1e4` reachable only via reflog — not from `main`, not from `origin/main`, its fix absent from `ImportController.java`, its regression test absent from the working tree. Caught by this session's own pre-push habit (`git fetch` + compare before every push, per this plan's own established practice after the V75 and ad13f30 incidents) rather than by anyone noticing the fix was gone. **Recovered**: `git cherry-pick 1c5b1e4` onto the current `main` tip (applied cleanly, no conflicts), verified the fix and its test were actually present in the tree — not just that the cherry-pick command exited zero — then pushed as `59daf00`. The other session's own unrelated uncommitted work in this shared directory was stashed before the cherry-pick and popped back afterward, verified byte-for-byte restored, not just "stash pop didn't error." No engineering content changed beyond restoring what was already written and tested | Third occurrence of the same failure class in this plan's own history (V75 migration collision, this file being silently reverted twice, now a real commit). The pattern is now well-enough established that it isn't worth re-diagnosing each time — verify before every push, investigate anything unexpected before touching it, and record what happened plainly rather than quietly recovering and moving on, per [[parallel-sessions-on-finora]] |
| 2026-08-14 | **BH-007 closed CLOSED–VERIFIED — bug-hunt P1 bucket now fully closed.** Re-verified against current code before fixing (line numbers had drifted since the 08-08 report from BH-041/BH-044 both touching `ReconciliationService.java`), confirmed still reproducing: the refund pass's only amount guard was per-pair, so N income rows each ≤ one EXPENSE could each independently match it, silently excluding real income from every total. Fixed in [PR #89](https://github.com/siddharth705/finora/pull/89) (merged `0d15f74`) by tracking cumulative refund capacity per expense across the pass, seeded from already-resolved `REFUND` rows so it holds across separate runs, not just one — proven complete for both entry points via the existing `CANDIDATE_WINDOW_DAYS >= REFUND_WINDOW_DAYS` invariant. Meets this plan's own VERIFIED bar: two regression tests mutation-checked via `git stash` on just the source fix, both confirmed to fail against the pre-fix code with the exact reported symptom, then confirmed passing restored. Full backend suite green. No date change | Same discipline as BH-048 earlier today: re-verify against current code rather than trust a stale line-number reference forward, and close a finding the moment it is actually confirmed closed, not at the next scheduled re-baseline |
| 2026-08-14 | **BH-048 closed CLOSED–VERIFIED.** PR #88 merged (`bd5dcd2`); a manual `workflow_dispatch` of `e2e-nightly.yml` run directly against the merge commit completed `success` ([run 31774202063](https://github.com/siddharth705/finora/actions/runs/31774202063) <!-- synthetic-ok: public GitHub Actions run ID, not customer data -->), rather than waiting for the 03:00 UTC schedule or inferring from the PR's own smoke job. Meets this plan's own VERIFIED bar (§4's closure grades): the break was demonstrated (two real consecutive nightly failures), then demonstrated gone on the actual workflow. Bug-hunt P1 bucket now 1 open (BH-007 only). No date change | Closes the loop opened earlier today when BH-048's status was corrected from stale to accurate — a corrected-but-still-open finding is not the same as a closed one, and the plan should say which it is the moment it's actually known, not at the next scheduled re-baseline |
| 2026-08-14 | **§5a roadmap updated to reflect what the load-testing baseline actually found.** The baseline didn't just produce a number — it changed R-11 from "capacity unknown" to "capacity bottleneck identified, location known, fix not yet chosen," and the plan's own roadmap diagram was still showing "Load testing baseline → Railway Pro" with nothing in between, which would read to a later reader as "measured, nothing to act on" rather than "measured, found a problem, investigation pending." Inserted **Investigate measured bottleneck → Re-test baseline** between them, plus a small status table (baseline: Complete, bottleneck identified: Complete, root-cause investigation/remediation/re-test: Pending). **Deliberately left unscoped**: no specific fix (pool-size increase, query optimization, transaction-boundary changes, caching) is named, because the investigation that would choose between them hasn't run yet — naming one now would bias it. **No date change** — this is a documentation update tracking a status change, not new engineering | Owner's instruction: the plan should reflect the risk-status change immediately, not wait for the follow-up investigation to be scoped. Explicit owner constraint: do not write "increase HikariCP pool size" as the fix — that decision needs its own investigation (which lever: pool tuning, query optimization, transaction boundaries, or infrastructure) and its own choice of environment (local-only vs. after Railway Pro), not an assumption baked into the roadmap |
| 2026-08-14 | **Load-testing baseline run (§5a P1 item).** Three tiers (100/500/1,000 concurrent users) against a local docker-compose stack with 100 seeded users and 30,000 transactions. Result: clean at 100 users (0% errors), degrades sharply by 500 (4.4% errors, 13–15s p95) and further at 1,000 (7.3% errors, 37–40s p95) — root-caused in the backend's own logs to HikariCP pool exhaustion (`DB_POOL_MAX_SIZE:10`, already known from the architecture audit, now with a measured consequence). Memory was never a constraint at any tier. R-11 raised Medium → High and reworded from "untested" to "measured, not yet fixed." Full writeup: [`load-testing-baseline-2026-08-14.md`](../../investigations/performance/load-testing-baseline-2026-08-14.md). Reusable tooling committed: `scripts/load-test/{seed.py,loadtest.js,run.sh,README.md}`. **No date change** — this is the P1 baseline measurement itself, not a fix; exact ceiling between 100–500 and a Railway-specific number are follow-ups, not done here | Deliberately scoped per §5a and the owner's own framing: measure reality, don't chase a scale target. Local, not Railway, because pushing 1,000 concurrent connections at the shared deployed instance needs its own explicit conversation, not a default. The pool-exhaustion mechanism this baseline found is architecture-level and will reproduce on Railway regardless of the exact number there |
| 2026-08-14 | **Production-readiness gap list re-sequenced against the Railway Pro plan (new §5a).** Backup/restore verification moved off the v1.0 release-gate list to a post-Railway-Pro gate — R-4 re-scoped from High to Medium/Tracked, release criterion 3 and the database-restore runbook (criterion 7) both moved out of the v1.0 gate table, §10's Production Ready gate updated to reflect it, §9 Block E re-scoped from 4–6 d to 3–4 d. Load testing stays in P1, pre-Railway-Pro, and the caching-evaluation measurement step (audit finding: no Redis, no cache layer) is folded into that same load-testing work rather than run as a separate item. **No date change** — Block E was re-scoped, not shortened; the restore-drill effort moves out of the window rather than disappearing | Owner's sequencing decision, following the 2026-08-14 architecture/production-readiness audit: Railway Pro will materially change production capabilities, the team isn't finalizing production ops yet, and drilling a full DR process against infrastructure that's about to change risks redoing that work. This is a resequencing, not a scope cut — backup/restore stays tracked (R-4) and returns as a hard gate the moment Railway Pro is purchased |
| 2026-08-14 | **Process note, recorded rather than hidden: commit `ad13f30` (intended as a docs-only BH-048 correction) also committed and pushed 19 unrelated files** — `ImportJobController`, `ImportJob`, `ErrorCode`, `StatementAnalysisRecorder`, `ImportJobDto/Service/Worker`, the `V77__import_job_failure_code` migration, their tests, and the frontend `ImportTimeline`/`Import.tsx`/`endpoints.ts`/`importJob.ts` changes. These were already staged in this shared working directory's git index before this session ran its own `git add` — consistent with [[parallel-sessions-on-finora]], and not caught because the pre-commit diff was checked scoped to the one intended path (`git diff --stat <file>`) rather than a bare `git status` first. **Owner reviewed and chose to leave the content as committed** — it reads as complete, coherent work (source paired with matching tests, consistent with the ongoing import-reliability effort), not a broken fragment. The commit message on `ad13f30` under-describes what it actually contains; this row is the correction for anyone reading git history later. No code was touched to produce this note | Same failure class that hit this file's own history twice already (V75 migration collision, this file being silently reverted) — a shared, uncommitted git index is state a concurrent session can collide with, whether the collision lands in code or in a commit boundary. Recorded per this plan's own standing practice: state what was lost/misattributed plainly rather than quietly working around it |
| 2026-08-14 | **BH-048 re-diagnosed; recorded status was stale.** The "e2e-nightly.yml has never executed" framing (carried from the 08-09 hunt report through this plan's 08-11 baseline) was wrong — the workflow has run 5 times. The real, current defect: it failed the last two consecutive nightly runs (08-12, 08-13), root-caused to two 08-11 display-only date-formatting commits whose E2E assertions were never updated. Fix pushed, verified against a live local stack, PR open (#88, not yet merged). Also corrected an internal inconsistency in this plan: §1 read "0 Critical, 0 High" while §4 already listed BH-048/BH-007 as open P1s — these are two different severity scales (security-audit vs. bug-hunt P0-P3) that were never labeled as such. No date change; recalculated on request, not from a new engineering session finishing work | A stale defect description is worse than no description — the plan is only useful if the next reader can trust "still open" means what it says. Caught by re-deriving BH-048's status from actual CI run history and commit diffs rather than re-quoting the 08-09/08-11 record forward unchecked |
| 2026-08-11 (post-audit) | **External uptime monitoring — Verified.** Configured directly in Better Stack (HTTP monitor, `https://api.finoratech.info/actuator/health`, expects `200` + `"status":"UP"` content match, 2-consecutive-failure down condition), observed reporting `UP`. Documented in `ops/monitoring/README.md` (commit `eadea35`) — no credentials/API keys committed, no Prometheus/Grafana config touched, `scripts/check-dashboard-metrics.py` re-run clean. **Separately noted: application performance monitoring was explicitly not this task's goal** — this closes "is anything watching production from outside," not "do we have full observability." R-09 (the audit's external-monitoring finding) is now closed, not just characterized | Second of three post-audit action items closed. Deliberately did not reopen the audit or expand scope to APM/Prometheus config, per explicit instruction — this was a documentation-closure task following an already-completed external configuration step, not new engineering |
| 2026-08-11 (post-audit) | **Railway config item closed — owner-confirmed directly.** `JWT_SECRET`, `STATEMENT_STORAGE_PROVIDER`, `TRUST_PROXY_HEADERS` confirmed correctly set in the production Railway environment. No values were shared in chat, per the audit's own instruction; asked for a per-setting yes/no once to make sure the blanket "correct setup" answer wasn't glossing over one of the three, owner reaffirmed at the same confidence without a breakdown, accepted as **Verified — owner-confirmed** rather than pressed a third time. Remaining from §32: the restore drill and external uptime monitoring, both still needing the owner directly | Closes the fastest of the three post-audit action items. This is real evidence (the account owner checking his own infrastructure dashboard), not a fabricated or inferred claim — distinct in kind from the earlier incident where an agent's confirmation was rejected for using invented terminology no one had actually checked |
| 2026-08-11 (audit) | **Full production-readiness audit completed and remediated.** 11 independent domains (auth, IDOR/BOLA, financial correctness, statement import, infra/network, database/migrations, admin RBAC, broader security, reliability/observability, test execution, product completeness), every P0/P1 finding put through independent adversarial verification before being accepted. Result: **0 P0/P1 security or IDOR defects found** — real corrections landed on roughly half the findings sent to verification, narrowing or refuting several rather than confirming them as first reported. Six real fixes implemented, each independently verified, all merged: a broken `main`-branch test (self-inflicted, from the earlier docs reorg — fixed same day); CSP/HSTS/X-Frame-Options added to both Cloudflare Pages frontends; admin-portal migrated off `localStorage` onto the same HttpOnly-cookie flow the user frontend already uses (live-verified against production CORS config); 5 previously-untested admin controllers now covered by 38 new integration tests; V73/V74 migration confidence built via 7+3 adversarial synthetic scenarios against real Postgres (no bug found, self-coherent by construction); a full-Spring-context boot test proving the forgot-password mitigation actually prevents the app from serving traffic, not just that a method throws. Completion **82% → 83%**. No date change — this work ran parallel to, not on, the critical path | Requested explicitly by the owner as a conservative, evidence-based launch audit distinguishing Verified/Code-confirmed/Not verified/Not applicable, specifically to avoid the failure mode of an agent trusting its own first-pass findings. Three items remain that need the owner directly, not more engineering: Railway config confirmation (`JWT_SECRET` strength, `STATEMENT_STORAGE_PROVIDER=r2`, `TRUST_PROXY_HEADERS`), a real database restore drill, and standing up external uptime monitoring — all require dashboard/account access this session doesn't have |
| 2026-08-11 | **§8a extended: Fino V2 readiness contract added (`docs/roadmap/fino-v2-readiness.md`).** NOW scope limited to opportunistic-only foundation work riding inside existing bug-hunt/release-gate fixes; provenance, event/audit trail, and admin analytics expansion moved to V1.0.1. No date change, no new §9 line items | Owner proposed a broader "Fino Readiness" workstream with several P1/P2 items marked build-now. The table's own later section resolved its internal inconsistency (admin analytics/audit trail listed both NOW and V1.0.1); adopted the disciplined split — nothing enters v1.0 as dedicated, scheduled work; only refactors that are already justified on their own merit and already touching in-scope code |
| 2026-08-11 | **New §8a: Fino (AI financial intelligence layer) recorded and explicitly parked as post-v1.0.** No date change, no scope change | Owner proposed a well-formed AI-assistant architecture (controlled backend tools, model never touches Postgres directly, reuse `DashboardService` logic). Captured as a V2 discovery/product proposal rather than started now, to avoid adding AI-dependency, schema, and UI scope to an already at-risk GA. Third option applied: commit to the product direction without committing engineering capacity |
| 2026-08-09 | Initial baseline. 65% complete, Target 2026-09-19, health **At Risk** | First PM baseline, derived from the repository at `661edce` |
| 2026-08-09 | **D-1 resolved (no real users).** Health **At Risk → On Track**. R-3 closed, R-3a opened | The defect backlog is pre-launch, not live. Nothing is being harmed while it is open, and no data repair is needed |
| 2026-08-09 | **D-2 resolved (mobile in v1.0).** Target **2026-09-19 → 2026-10-16**; best case 2026-09-28, conservative 2026-11-13 | Mobile joins the critical path. +13–19 working days, of which the externally-gated items (enrolment, device bring-up, store review) set the floor. R-9 elevated; R-9a and R-9b opened |
| 2026-08-09 | Capacity confirmed at ~10 h/day. **No date change** | The velocity baseline was measured from 105–106-commit days; 10 h/day is the input that produced it, so this validates the estimates rather than moving them |
| 2026-08-09 (eve) | **Scope change accepted: the full Medium/Low backlog (S2, S3) moves from v1.1 into v1.0.** Target **2026-10-16 → 2026-11-06**; Best 2026-09-28 → 2026-10-12; Conservative 2026-11-27 → 2026-12-11 | Owner's decision, made after the impact was stated and then reaffirmed. 10–12 working days of P2/P3 work now sits ahead of a calendar-gated critical path. BH-017, BH-025 and BH-044's retention window stay out of scope — they are product decisions, not work. Remediation launched on `fix/bug-hunt-medium-low` |
| 2026-08-09 (eve) | **Parallel-work conflict caught and resolved before any duplicate work landed.** A separate live session had opened `perf/bh-042-measurement` off the same base, uncommitted, to pick up the same performance cluster. `fix/bug-hunt-medium-low`'s Tier 3 (BH-042, 043, 045) **descoped from it** — that session keeps the cluster; the sweep finishes Tier 1 + Tier 2 (7 items) only. **No date change**; Tier 3 was already the least certain part of the 10–12 day estimate | PM instruction #12 in practice: two sessions targeting the same finding, caught via `git worktree list` + process inspection before either wrote code, not after a merge conflict forced the question |
| 2026-08-09 (eve) | **Re-baselined again after 6 more merges.** Completion **77% → 80%**. BH-023, BH-014, BH-050, BH-031 closed; BH-006 remains, **blocked on decision D-13**. **All three dates hold** | The defect track is now effectively finished as engineering work. Dates did not move because they never depended on it. **Warning recorded in §8:** the two workstreams that gate the date — mobile (65%) and infrastructure (57%) — did not move at all today, while the OCR track merged four PRs |
| 2026-08-09 (pm) | **Re-baselined after Bug Hunt Round 1 merged (PR #63) and 7 further PRs.** Completion **65% → 77%**. Open findings **1 Critical + 12 High → 1 High**. **All three dates hold** | Round 1 closed 31 of 61 findings and came in ahead of blocks A and B. The gain was partly given back: the closure report disclosed `PdfTableLocator` and `imports/product/` as never reviewed, which is new work of comparable size, and Round 2 now exists as a defined scope. Dates are unchanged because the critical path was never the defect backlog — it is the Google closed-test clock |
| 2026-08-09 | **D-5 resolved by PR #44** — poll at 100 ms, async threshold question dropped | Measured rather than argued: the poll interval was ~98% of the queueing penalty, so the threshold was aimed at the wrong variable |
| 2026-08-09 | **BH-031 closed** (PR #65) — the `prod` profile *is* active on Railway | Closed by probing behaviour the variable controls (`/v3/api-docs` → 401) rather than by reading a setting. Stronger evidence than the variable itself would have been |
| 2026-08-09 | **D-9 resolved (individual accounts, no legal entity).** **No date change** — Best 2026-09-28, Target 2026-10-16, Conservative 2026-11-27 all hold. D-10 and D-11 activated; D-12 raised; R-14 elevated; R-15 opened | The two effects cancel almost exactly: Apple's individual path is the fast one, and Google's 12-tester gate is the slow one. What *does* change is certainty — the 2026-09-12 closed-test milestone is now binding rather than conditional, and it is the only dated commitment in the plan |
| 2026-08-09 | **D-8 resolved (neither store account exists).** Conservative **2026-11-13 → 2026-11-27**. Best and Target held, but Target is now conditional on a dated milestone. New §9a; R-13 and R-14 opened; D-9 and D-10 raised | Store enrolment became the longest-lead item in the project. Apple's tail (2–7 weeks, reported) blocks iOS device bring-up because APNs needs a paid account; Google's 12-tester/14-day clock cannot start until an installable build exists. **The mobile-first sequencing recommendation is now a requirement, not a preference** — sequencing mobile after web makes the Target date unreachable regardless of hours worked |
| 2026-08-09 (late) | **Closure push: 3 PRs merged (#67 BH-046 step 1, #72 BH-058 sweep, #74 CORS + DB password), D-13/BH-006 resolved and fixed (PR #75, open), D-3/BH-017 and BH-025 both resolved with implementation PRs building.** No date change | BH-046 step 1 confirmed safe to merge only after a direct owner check that `STATEMENT_STORAGE_PROVIDER=r2` is actually set on Railway prod — an earlier automated answer claiming this was already confirmed, with fabricated terminology ("Gate 1/2", `legacy_only_blocks_step2`) not present anywhere in the repo, was rejected rather than acted on. BH-006's reproduction (a fabricated row confirmed through `/reimport/confirm` and posted to the ledger) matches the BH-023 pattern exactly, closed the same way: re-parse the stored bytes and run `ConfirmedRowIntegrity` against them |
| 2026-08-09 (night) | **A live CI break on `main`, caused by two concurrent PRs (mine and a parallel session's) each independently claiming Flyway version 75.** Root-caused from the CI log, fixed within the hour by renumbering to V76, revalidated against real Postgres. **A real defect found in PR #75's own fix**: `ConfirmRequest` always passed `password=null`, so BH-006's "double prompt" was actually an unconditional failure for every password-protected reimport. Fixed by a separate concurrent session via a direct commit to `main` (`4133910`) — verified end-to-end against a real encrypted PDF. **I had reported the double-prompt framing to the owner as a UX tradeoff he approved; that framing was inaccurate and sourced from the PR description rather than independent testing** — recorded here rather than smoothed over. Apple Developer Program enrolment submitted as Individual, after an initial wrong turn into the Organization/DUNS flow, corrected. Completion **80% → 82%** | Two parallel-session collisions in one night, one caught pre-merge (BH-032/036 duplicate), one not (V75). The pattern is now established enough to warrant a standing practice change, not just a one-off note |
| 2026-08-10 (morning) | **This plan file was silently reverted to its post-PR-#76 state overnight** — a parallel session, operating in this same shared directory, checked out or overwrote it while the previous night's edits sat uncommitted on disk. Reconstructed from this session's own transcript and **committed this time**, specifically to prevent a third loss. `main`'s CI confirmed fully green: the V75 fix held, and a same-commit `BulkRecategorizeLearningIT` failure was rerun and confirmed a flake (unrelated file, symptom matches the async-queue timing-race pattern flagged repeatedly tonight), not a regression. Four small import-pipeline commits landed direct to `main` (CSV opening-balance double-count, amount-column merge guard, an executable-bit fix, one new backlog item — a non-text header row diagnosed against a real SBI statement, correctly logged rather than built ad hoc). Completion **82%, effectively unchanged** — small fixes and one small newly-logged gap roughly offset. **No date change** | Third re-baseline in a row where infrastructure (workstream 6) shows an identical number — the clearest available evidence that the date depends on store enrolment and device bring-up, not on code |
