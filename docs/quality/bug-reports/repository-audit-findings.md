# Repository audit — deferred findings

Produced by the repository-wide production-readiness audit of 2026-08-05. Four parallel passes
(backend, user frontend, admin portal, mobile/infrastructure/CI/docs) fixed every issue with a
clear, technically correct solution and committed each one separately. This document records only
the items that were **deliberately not implemented**, and why.

An item lands here for one of three reasons:

1. It is a product or process decision, not an engineering defect.
2. It has several defensible architectural answers and picking one unilaterally would violate the
   standing rule that a bug fix must not carry an architectural change with it.
3. It is a performance or schema change whose benefit has not been measured, and the standing rule
   is to measure before *and* after rather than ship a plausible-sounding optimisation.

Nothing here is a known-broken-and-ignored defect. Where something is exploitable or user-visible,
the exploitable part was fixed and only the residual decision was deferred — those cases say so
explicitly.

---

## 1. Already-stored URLs are not retroactively validated

**Area:** backend · **Category:** security follow-up · **Needs:** a decision from the maintainer

`Bank.websiteUrl` and `Merchant.website` now reject non-`http(s)` values via the `@SafeHttpUrl`
constraint, and the admin portal refuses to render an unsafe URL as a link. Both of those guard
*new* writes and *rendering*. Neither changes rows already in the database.

**Why deferred:** what to do with a non-conforming existing row is a judgement call. Nulling the
column is destructive and irreversible; leaving it is safe today only because the render guard
exists. That is a data decision, not a code fix.

**Recommended first step** — a read-only detection query, which very likely returns zero rows
(custom banks are rare and admin-created):

```sql
SELECT id, website_url FROM banks
WHERE website_url IS NOT NULL AND website_url <> ''
  AND website_url !~* '^https?://[^[:space:]]+$';

SELECT id, website FROM merchants
WHERE website IS NOT NULL AND website <> ''
  AND website !~* '^https?://[^[:space:]]+$';
```

If it returns nothing, no migration is needed at all. If it returns rows, capture the old values in
the audit log inside the same migration before nulling them, rather than silently discarding data.

**Related, deliberately left unannotated:** `Merchant.logoUrl` has a setter but no caller anywhere
in the codebase — it is unreachable by any request today. This is recorded in `SafeHttpUrl`'s
javadoc so that whoever adds the first write path knows the constraint belongs there.

---

## 2. Query errors render as "no data" across most admin tables

**Area:** admin portal · **Category:** correctness / UX · **Effort:** moderate (~10 call sites)

`SystemHealth` and `Diagnostics` returned `null` on a failed fetch — a completely blank page — and
that was fixed. The same root cause remains on roughly ten other pages (`Users`, `Banks`,
`GlobalRules`, `MerchantIntelligence`, `LearningEngine`, `PlatformAnalytics`,
`ReconciliationMonitor`, and `EntityDrawer`'s tab components such as `BankAuditTab`): they pass only
`isLoading` into `DataTable`, never `isError`, so a failed request settles into the empty state and
is indistinguishable from genuinely-empty data.

**Root cause:** `DataTable` was designed with three states — loading, empty, populated. There is no
fourth error state in its contract.

**Approaches:** (a) add an optional `isError`/`errorMessage` prop to `DataTable` and update every
caller — consistent, but changes a shared component's contract and touches ~10 files, each wanting
its own regression test; (b) fix only the highest-traffic pages — an arbitrary cutoff that leaves
the codebase internally inconsistent.

**Recommended:** (a), as its own reviewed change with per-page test coverage.

**Why not auto-implemented:** extending a shared component's contract is an architectural change,
and doing ten call sites properly needs per-site verification rather than being bolted onto a bug
hunt.

---

## 3. ~~`actingAdminId` omission is a recurring bug class with no automated guard~~ — RESOLVED

**Area:** backend · **Category:** security / architecture · **Closed by** `AuditActorAttributionTest`

Approach (c) was implemented. The guard fails the build when a method that writes an audit entry
becomes reachable from an admin controller without a parameter naming who acted.

The concern that made this a deferred item — "that requires a maintained exceptions list" — turned
out to be smaller than feared, but only because the rule is scoped by *reachability* rather than by
"does this method take an actor". 27 of the 64 audit writers legitimately have a single actor
(`USER_LOGIN`, `SETUP_COMPLETED`); judging them all would have needed a 27-entry allow-list, which
is a list nobody maintains honestly. Walking transitively from admin controller handlers and judging
only what is actually reachable brought the allow-list to **two** entries, both system passes
(`RECONCILIATION_RUN`, `RECURRING_DETECTION_RUN`) that an admin reaches only as a downstream side
effect of an action already audited with its own actor.

Verified against a deliberately unattributed fixture, against a real regression (stripping the actor
from `AccountService.delete` made the rule report exactly that method), and with a floor assertion so
a silently-resolving-nothing walk fails red rather than passing forever.

Known limitation, recorded in the test: it proves an actor is *available* to the method, not that it
is written into the metadata as `actorId`. Inspecting `Map.of(...)` arguments is not something
bytecode analysis gives cheaply, and the historical failure was always an absent parameter.

<details>
<summary>Original finding</summary>

This audit fixed six services that recorded audit entries without recording *which admin* acted
(`RoleService` assign/revoke, `RuleService` create/update/delete, `AccountService`
create/update/delete, `TransactionService.delete`, `confirmMerchantCategory`). A previous pass fixed
two more. Eight occurrences of one shape, all caused by admin proxy controllers being added later
against services originally written for self-service.

**Why deferred:** a static check would need to distinguish "this service method is reachable from
both a self-service and an admin controller" from "this action legitimately always has one actor"
(`USER_LOGIN`, `SETUP_COMPLETED`). That requires a maintained exceptions list — a rule *plus* its
allow-list is new architecture, not a minimal fix.

**Approaches:** (a) status quo, catch it in the next audit; (b) a code-review checklist item;
(c) a heuristic ArchUnit rule with an explicit allow-list.

**Recommended:** (c), as its own reviewed change. Given this shape has now recurred eight times
across two passes, it is the highest-value item in this document.

</details>

---

## 4. ~~Content-addressed storage does not verify content on read~~ — RESOLVED

**Area:** backend (`imports/storage`) · **Category:** data integrity · **Closed by** `ContentAddress.requireMatches()`

Verification happens at **`StatementContentService.read()`** — the single choke point every read
already goes through, so a future R2/S3 provider inherits the guarantee rather than having to
reimplement it.

> **Updated 2026-08-05, after `cdae4c8`.** This originally described a second site,
> `StatementBackfillWorker.write()`, which read each object back before recording its address. That
> class no longer exists: the backfill was deleted once it was established there is no historical
> statement content to migrate (development database has no schema, R2 bucket reports 0 objects).
> The read-back argument was sound *for a backfill* — it was the last moment `file_content` still
> existed to compare against, and it mattered most for deduplicated rows attesting to bytes an
> earlier row wrote. With no backfill, none of those rows exist. The read-path check is unaffected
> and is now the whole of this finding's resolution.

**Two things in the original deferral turned out not to hold**, which is the part worth carrying
forward:

1. *"A real SHA-256-per-read cost."* There are five read sites, all user-initiated — import confirm
   (×2), download, re-import (×2). A hash over a few MB is invisible beside object-store latency and
   PDF parsing, and none of it is on a hot path. The cost objection was reasonable in the abstract
   and simply did not survive looking at the call sites.
2. *"Verify during the backfill, since every object is read once anyway."* The backfill read
   `file_content` from the **database** and wrote *to* storage; it never read back. The read-back
   had to be added deliberately rather than folded into a read that was already happening — and the
   backfill has since been deleted entirely, so deferring verification to it would have deferred it
   to nothing.

`StatementIntegrityException` extends `StatementStorageException` rather than reusing it: *missing* is
an availability problem that may resolve when a provider recovers, *corrupt* is a correctness problem
that returns the same wrong bytes forever. Same supertype, so no caller changes; separable, so
alerting can tell a transient outage from a data-integrity event.

**Where the code landed.** The implementation is in commit `6b786b2`, whose message describes only the
Repository Guardian work. Two sessions were working in one tree and one git index; that commit picked
up both changes. Nothing is missing or wrong in the code — this note exists so the storage-integrity
rationale is recorded somewhere, since the commit message does not carry it.

**Smaller storage notes from the original finding, still open:** the orphaned `.partial-*.tmp` on a
JVM kill between `createTempFile` and `ATOMIC_MOVE`, and the theoretical Windows `ATOMIC_MOVE` failure.
Both remain deferred to a future unreferenced-object sweep, unchanged by this work.

**Two smaller storage notes, both genuinely minor:**

- A JVM kill between `createTempFile` and `ATOMIC_MOVE` leaves an orphaned `.partial-*.tmp` in the
  shard directory forever. It is never addressable and self-limiting; it belongs to a future
  unreferenced-object sweep, not a per-write fix.
- On Windows, `ATOMIC_MOVE` onto a target another process holds open could in principle throw.
  Could not be reproduced, and filesystem storage is explicitly dev/test-only, so no fix was
  guessed at.

The rest of the storage layer was audited against path traversal, resource cleanup, write
atomicity, the exists-then-write TOCTOU, hash correctness, error-message leakage and file
permissions, and was found correct. No changes were made to it.

---

## 5. Unmeasured performance and schema items

**Area:** backend · **Category:** performance · **Deliberately not optimised**

The standing rule is to measure before and after and demonstrate a *net* benefit, rather than ship a
change that merely shifts cost. Neither of these was measured, so neither was touched.

- **`LayoutIntelligenceService.driftingLayouts()` re-scans the full statement-imports table once per
  layout.** It calls `timeline(fingerprint)` per layout with `usageCount >= 4`, and each call
  independently re-runs `findAllWithLayoutFingerprint()` with no `WHERE` on fingerprint and no
  pagination. Real redundancy, but this is a rarely-hit platform-wide admin diagnostics endpoint,
  not a hot path. Fix candidate: a per-fingerprint repository query, or filtering the already-
  fetched `layoutOverview()` list in memory — but only after a timer confirms it matters at real
  table sizes.
- **No index on `statement_imports.layout_fingerprint` / `import_sessions.layout_fingerprint`,**
  despite `WHERE layout_fingerprint IS NOT NULL` being the core query since V39. Worth a partial
  index once the table is large enough to matter; adding it now optimises a table that may not need
  it and costs write throughput.

---

## 6. `check-imports.py` cannot gate anything until it has an accept-list

**Area:** scripts / CI · **Category:** tooling

Its sibling `check-xml-comments.py` had the same defect — no `exit()` call at all, so success and
failure were indistinguishable in the exit code — and that one **was** fixed and wired into the
backend CI job, because it runs clean against the current tree.

`check-imports.py` cannot get the same treatment yet: it has three documented, hand-verified false
positives (FP-01/02/03) still active. Making it `exit(1)` unconditionally would fail every future
clean run.

**Recommended:** give it an `ACCEPTED_FALSE_POSITIVES` list of (file, type, package) tuples — the
same shape `check-dependency-advisories.py` already uses for its `ACCEPTED` advisories — fail on
anything not in that list, then wire it into the backend CI job.

**Why not auto-implemented:** the accept-list mechanism is a new capability, not a minimal fix.

---

## 7. Product and process decisions

These are not defects. Each needs a call from the maintainer.

**`CONTRIBUTING.md` describes a branching model the repo has never used.** It documents full
GitFlow (`main`/`develop`/`release/*`/`feature/*`/`bugfix/*`/`hotfix/*`), but no `develop` or
`release` branch has ever existed — history is `main` plus short-lived branches merged directly.
This may be aspirational policy for when the team scales rather than a factual error, so correcting
it is a process decision, not a doc fix.

**~~The `support@` and `careers@` mailto links still point at the old `finora.app` domain.~~**
RESOLVED. They appeared in six frontend files (`TopBar`, `Help`, `Careers`, `Landing`, `Privacy`,
`Terms`) while the domain had migrated to `finoratech.info`. The `support@` copies were routed
through `frontend/src/lib/contact.ts`; `careers@` followed, via `CAREERS_EMAIL` in the same module.

Worth recording that the fix took two passes. Centralising the support address left `Careers.tsx`
still holding its own inline literal, because nothing failed when five copies were fixed and the
sixth was not -- the same unenforced-duplication shape as the client auth policy drift. So the
durable half of the fix is `scripts/check-contact-addresses.py`, which fails on any hardcoded
Finora mailbox under a scanned app's `src/`, wired into CI and pre-commit. The next domain
migration cannot silently miss a copy.

One thing this cannot verify: that `careers@finoratech.info` and `support@finoratech.info` actually <!-- synthetic-ok: Finora's own mailboxes, not customer PII -->
receive mail. The addresses are now correct and consistent, but mailbox routing lives in the domain
and email-provider dashboards, not the repository.

**Settings → Data has no export or delete-account control.** The portal boundary taxonomy lists
these as User Portal scope, but the page currently shows only import statistics. This is a feature
gap requiring backend capability design and explicit approval before any frontend work — not a bug,
and deliberately not built speculatively.

**`mobile/package.json` has no `engines` field,** unlike `frontend`/`admin-portal` which both pin
`>=20.19.0`. No failure today because CI hardcodes `node-version: '20'`. A one-line addition if
consistency is wanted.

**`ForgotPassword.tsx`'s dev-only `devResetLink`** is rendered via a raw `<a href>` with no
`isSafeHttpUrl` guard. The field is null once real email delivery is configured, and the
exploitation path requires an attacker crafting their own request's `Origin` header — which only
affects their own response. Left as a cheap hardening opportunity rather than a rushed change,
since the file has no dedicated test to verify a fix against.

**Missing page-level tests:** `Dashboard.tsx` and `ResetPassword.tsx` (admin portal) have no
dedicated test file. `ResetPassword` has real logic — OTP flow, password strength — currently
covered only by the accessibility sweep. Both are substantial test-authoring efforts rather than
audit bolt-ons. Similarly, `AdminAccountController` and `AdminTransactionController` have no
dedicated integration test class, unlike their siblings; the fixes this audit made to them are
proven at the unit level.

---

## Verification status at time of writing

| Suite | Result |
|---|---|
| Backend, full `./mvnw test` | **1167 run, 0 failures, 0 errors, 0 skipped** |
| — of which Testcontainers integration tests (`*IT`) | 117 across 30 classes, 0 failures |
| User frontend | 160/160 passing, build and lint clean |
| Admin portal | 221/221 passing, build and lint clean |
| Mobile | 135/135 passing, typecheck and lint clean |

The integration suite was blocked for part of the audit — Docker Desktop stopped running on the
development machine, so all 117 Testcontainers tests failed with `Could not initialize class
AbstractIntegrationTest` (a missing Docker environment, zero assertion failures). Docker was
restarted and the full suite re-run against the merged tree; the numbers above are from that clean
run, so there is no outstanding local certification gap.

Worth knowing for the next person who hits this: `backend/target/surefire-reports/` is not cleared
between runs, so aggregating it after a partial run silently mixes in stale results from previous
ones. Check report mtimes against the run you actually care about, or `mvn clean test`.
