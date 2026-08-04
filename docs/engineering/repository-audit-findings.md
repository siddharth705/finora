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

## 3. `actingAdminId` omission is a recurring bug class with no automated guard

**Area:** backend · **Category:** security / architecture

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

---

## 4. Content-addressed storage does not verify content on read

**Area:** backend (`imports/storage`) · **Category:** data integrity · **Needs:** a design decision

`StatementStorage.retrieve()` returns the bytes at an address without re-hashing them. A
content-addressed store that never verifies on read cannot detect bit-rot or a mis-filed object — it
returns wrong bytes as though they were correct.

**Why deferred:** there are several reasonable answers (verify every read; sample a percentage;
verify only during the Phase 3 backfill) with a real SHA-256-per-read cost, and the interface
javadoc never promised verification. Choosing one is a design decision.

**Recommended:** verify during the Phase 3 backfill at minimum, since that is the point at which
every object is read once anyway and a mismatch is most actionable.

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

**The `support@` and `careers@` mailto links still point at the old `finora.app` domain.** They
appear in six frontend files (`TopBar`, `Help`, `Careers`, `Landing`, `Privacy`, `Terms`) while the
domain has migrated to `finoratech.info`. Whether the old mailbox still routes cannot be determined
from the repository, and guessing wrong silently breaks a real support channel. Confirm on the
domain dashboard, then it is a one-line replacement.

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
| Backend unit tests | 1052 run, 0 failures |
| Backend integration tests (`*IT`) | **117 could not run** — see below |
| User frontend | 160/160 passing, build and lint clean |
| Admin portal | 221/221 passing, build and lint clean |
| Mobile | 135/135 passing, typecheck and lint clean |

The 117 Testcontainers-backed integration tests could not be executed: the Docker daemon on the
development machine wedged mid-audit (`docker info` hangs; both named pipes absent) and did not
recover. Every failure is `Could not initialize class AbstractIntegrationTest` — a missing Docker
environment, with **zero assertion failures**. The usual remedy (`wsl --shutdown`) was not run
because it would kill unrelated work in progress.

This is a genuine gap in local certification, mitigated three ways: the same suite ran fully green
(1105/1105) earlier in the audit before Docker wedged, covering the first backend pass; the newly
constrained fields were checked by reading the integration tests' actual payloads, none of which
send a `websiteUrl` or `website` value that could newly fail validation; and CI runs the full
`./mvnw test` on a runner with a working Docker, so the push is verified there.

**Re-run the integration suite locally once Docker is healthy** rather than treating CI as the only
gate.
