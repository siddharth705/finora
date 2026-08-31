# Import Reliability Layer — Remediation Proposal

**Status:** Decision-support document, not a plan. Nothing here is scoped, sequenced, or approved.
Built from `docs/architecture/system-design/import-reliability-layer-audit.md` (read-only audit,
2026-08-12) plus a same-day spot-check of the code it cites. This document lays out the real options
per question the PM asked and their tradeoffs; it does not pick one. Where the author has a view it is
marked **View:** and is a single opinion, not a recommendation to execute.

**Relationship to other proposals:** `docs/proposals/data-import-intelligence-proposal.md` covers admin
observability *tooling* built on top of what already exists (dashboards, bank-identifier columns,
parser-version tracking) and is written from the same factual baseline as this document. That proposal
already scopes item §3.3 (parser version) and overlaps with question 6 below; this document does not
re-litigate it, only cross-references it where relevant. This document's focus is upstream of that one:
what happens to the *user's data and experience* when an import fails, not what admins see about
imports that succeed.

---

## 0. Corrections, unverifiable items, and the blocking prerequisite

Read in full before the rest of this document — several options below change shape depending on these.

### (a) Correction to the audit: a partial answer to the storage-provider unknown exists in the repo

The audit states (Cross-cutting note, and gap table row "Admin can retrieve a user's failed upload")
that whether `app.statement-storage.provider` is set in production **"could not be determined from the
repository."** That is no longer the full picture. `backend/src/main/java/com/finora/config/
ProductionConfigValidator.java` (merged 2026-08-09, three days before the audit — commit `2c22dd6`) now
**refuses to boot** when Spring's active profile includes `prod` and `app.statement-storage.provider` is
unset (`:164-172`). It throws before the HTTP connector binds, so a misconfigured deployment doesn't
serve a single request.

This narrows, but does not close, the unknown:

- **If** Railway activates the `prod` Spring profile for this service (standard for a "production"
  deployment, but the profile is set via a Railway-side env var and is not itself visible in this
  repository — `SPRING_PROFILES_ACTIVE` appears only in `docker-compose.yml` and CI workflow files, not
  in anything that reaches Railway), **and if** the app is live and serving traffic today (asserted by
  project memory, not re-verified here), **then** `app.statement-storage.provider` must already be set
  to something — the app could not have booted since 2026-08-09 otherwise.
- This says nothing about **which** provider (R2 vs. filesystem) or whether it's durable in the way R2
  is meant to be — a filesystem provider pointed at ephemeral Railway disk would pass this check and
  still lose bytes on redeploy.
- It says **nothing about `app.import.queue.enabled`** — that flag has no equivalent boot-time
  validator; it only becomes load-bearing (turns a silent-danger warning into a hard 503, per
  `ProductionConfigValidator.java:174-183`) when true, and defaults to false either way.

So the audit's headline framing — "both unknowns must be confirmed operationally" — is now more
precisely: **the storage-provider unknown is very likely resolved in one direction (something is set)
by the mere fact the app is running, but its identity and durability are not; the queue-enabled unknown
is completely open.** Section 0(c) below still treats both as blocking for cost-estimation purposes,
because "very likely set" is not "confirmed, and confirmed to be R2."

### (b) Audit claims relied on here without independent re-verification

Everything cited with a file:line in the audit that this document's spot-check re-read (`ImportService.java`,
`ExtractionCheck.java`, `ErrorCode.java`, `Import.tsx`, `StatementHistory.tsx`, `endpoints.ts`,
`StatementAnalysisRecorder.java`, `ImportJobService.java`, `application.yml`) matched current code
exactly at the time of writing, with the one addition in (a). Not independently re-read for this
document, taken on the audit's word:

- `ImportSessionService.java` TTL/sweep behavior (`:41`, `:111-138`) and `storeContent` reachability
  (`:217-225`).
- `StatementStorageSweepService.java` 90-day reclaim and 24h floor (`:87`, `:96`, `:127-136`).
- `AdminImportTraceController.java` permission gating (`PLATFORM_DIAGNOSTICS_VIEW`) and the full trace
  join in `ImportTraceService.java`.
- `AdminSystemController`/`AdminSystemService.recentImports` behavior (`:42-45`, `:74-95`).
- `ImportVerificationFinding.java` schema and repository.
- `GlobalExceptionHandler.java` behavior for 409/500 cases (`:136-142`, `:294-303`).
- The BH-025/BH-028/BH-046 bug-hunt cross-references (BH-046 is corroborated indirectly — see (a),
  `ProductionConfigValidator.java:148-163` names it directly and describes the same dual-write removal
  the audit references).

Given how active this repo is under parallel sessions (per project memory: a real Flyway migration
collision has already happened once), treat anything in this list as accurate as of 2026-08-12 but worth
a fresh grep before anyone begins implementation, not just before this document was written.

### (c) Blocking prerequisite question, restated plainly

Before several options below can be costed — "turn on what already exists" and "build the missing 15%"
are very different asks — someone needs to answer, directly against the Railway dashboard, not the repo:

1. Is `app.import.queue.enabled` `true` or `false` in the production environment right now?
2. What is `app.statement-storage.provider` set to (if anything), and if `r2`, are the
   `app.statement-storage.r2.*` credentials actually valid (a set-but-wrong credential would also have
   failed to boot per `ProductionConfigValidator`, so "booted" is decent evidence here, not proof of
   correctness under load)?

If the answer to (1) is already `true` and (2) resolves to a working R2 config, the async job queue —
state machine, 5 automatic retries, byte retention on failure — is **already protecting every user
today**, and most of the "turn on the queue" options below are a no-op that's already shipped; the
remaining work is entirely UI (exposing what the backend already does). If (1) is `false`, enabling it
is a live production behavior change to the primary upload path and should be costed and risk-assessed
as such, not treated as flipping a feature flag with no blast radius.

---

## 1. What happens when an import fails? (today vs. what's possible)

**Today:** on the default synchronous path, nothing durable and user-reachable is created. A telemetry
row (`statement_analysis_sessions`, outcome `FAILED`) is written, reachable only by an internal
reference the user is never shown, via an admin DTO that deliberately strips the two fields (`userId`,
`fileName`) a support engineer would need to find it from a complaint. The user sees an error message
and the page they were on; nothing persists across a page refresh.

| Option | What it fixes | What it doesn't fix | Cost/risk |
|---|---|---|---|
| **A. Do nothing** | Nothing | Everything below | Zero cost, but every gap in this document remains, and it compounds with real financial data already live in production per project memory |
| **B. Turn on `app.import.queue.enabled` in production** (contingent on 0(c)) | Every sync-path gap at once: durable `ImportJob` record, 9-state machine, automatic retry, byte retention on failure, admin trace already wired to it | Frontend still doesn't surface job state to users (§4), admin still can't look up by user/email (§5) — the machinery exists but its UI doesn't | If 0(c) resolves to "already on," this is free — a documentation/verification task, not engineering. If off, this is a real behavior change to the primary upload path (a different worker, different latency/timeout characteristics, a queue that can back up) and needs load-testing and a rollback plan, not a same-day flip |
| **C. Add byte persistence directly to the sync failure path** (a targeted fix, not the full queue) | The single sharpest gap: a failed sync upload currently has no bytes anywhere. Call `storeContent`/`createSession`-equivalent persistence before the extraction check runs, tagged with a `FAILED`-capable status, independent of the queue flag | Doesn't give a state machine, doesn't give automatic retry, doesn't give admin lookup-by-user — narrower than B | Smaller, more contained change than B — touches `ImportService.parseAndStageWithSession`/`parseAndStagePdfWithSession` rather than the request-routing layer. Still needs the storage-provider question from 0(c) answered, since this reuses `StatementContentService`, whose Postgres-`bytea` fallback for an unset provider is explicitly the thing `ProductionConfigValidator` now refuses to allow into production for the reasons in its own comment (`:148-163`) |
| **D. Write a durable `ImportSession`-equivalent row on failure, without storing bytes** | Gives failures a first-class, listable, user-visible record (closes most of §3's gap) without touching storage | Bytes still gone; user still re-uploads to actually retry — partial fix that could read as "fixed" when retry still isn't possible | Cheapest of the three real options; risk is presenting users a "failed import" they can see but can't do anything about except start over — may need explicit UX framing so it doesn't read as broken |

**View:** C and D are not mutually exclusive and are cheaper to reason about independently than B is as a
single unit — C solves retry, D solves visibility, and either can ship before an operational decision is
made about turning the whole queue on. B is the "do it once, correctly" option but it inherits the
async path's existing UI gaps (§4, §5) as-is, so choosing B alone without also doing the UI work in
those sections would fix the backend and leave the user-facing experience exactly as blind as today.

---

## 2. Is the original PDF retained? (per failure mode)

| Failure mode | Bytes retained today? | Notes |
|---|---|---|
| Password required / wrong password | No | Same sync path as every other failure — the file never reaches `storeContent` |
| Scanned/OCR-needed, no-table-found, zero-transactions | No | Same |
| Corrupt/truncated PDF, parser crash | No | Same |
| Staged but not yet confirmed (a successful parse the user hasn't clicked "confirm" on) | Yes, 48h | Different code path — session creation happens before this state, so bytes exist |
| Confirmed import | Yes, indefinitely (90-day reclaim after deletion) | Not a failure case, listed for contrast |
| Async job, any outcome including FAILED | Yes | Bytes stored before the job row exists, independent of outcome — this is the one place failure already retains bytes today, gated entirely on whether the queue is on |

**The critical gap is exactly the sync-failure row: zero retention.** This is the same fact as §1's
options B/C — retention and retry are the same problem here, not two separate ones. There isn't a
retention-only fix cheaper than C above; storing bytes without also creating a record to reference them
(§1D) would leave orphaned files with nothing pointing at them, which is arguably worse than the current
state (an unreferenced-bytes cleanup problem instead of a re-upload inconvenience).

**View:** if only one thing from this whole document gets funded, this is the one with the clearest
harm-to-a-real-user story — someone whose bank statement fails to parse today has to find the PDF again
(email, downloads folder, bank portal) and re-upload, which is friction, not data loss, but it's the one
gap that's a straightforward, bounded fix (§1C) rather than a UI or process change.

---

## 3. Can the user retry without re-uploading?

| Path | Retry without re-upload? |
|---|---|
| Confirmed import | Yes — a real "Reimport" button exists and works today (`StatementHistory.tsx`) |
| Staged session, browser lost before confirming | Backend supports it (`GET /import/sessions{,/{id}}`); **frontend never calls it** — `importApi.listSessions`/`discardSession` are defined and referenced only from `Import.test.tsx`, not from any page a user can reach |
| Async job that failed | Backend retries automatically, 5 attempts with backoff — no user action needed, but also no user-visible retry button, and this only fires when the queue is on |
| **Sync upload that failed to parse** | **No.** Must re-upload from scratch — this is the common case today, since the queue defaults off |

Two distinct kinds of "not fixed" are bundled in this section, worth separating because they have very
different costs:

- **The staged-session frontend gap** is cheap — the backend endpoints already exist and are already
  tested against (see the test file mocks). This is a UI-only task: a "Resume" affordance somewhere a
  user would look (import history, or a banner on the Import page itself), wired to endpoints that
  already work. No backend change, no migration, no production-config dependency.
- **The sync-failure retry gap** requires §1's B or C — there is no cheap version of this one, because
  there's genuinely nothing server-side to retry against without one of those.

**View:** the staged-session gap is the easiest win in this entire document — it's UI work over an
already-built and already-tested backend, with zero coupling to the two production-config unknowns.
Worth considering independently of everything else here, on its own timeline.

---

## 4. What does the customer see / could see?

Backend differentiates cleanly: `ErrorCode.java` has distinct codes for no-header-found (`IMPORT_001`),
zero-transactions-found (`IMPORT_007`), password-required (`IMPORT_008`), wrong-password (`IMPORT_009`),
and OCR-required (`IMPORT_010`), each carrying a purpose-written message. Confirmed by direct read:
`frontend/src/api/errorCodes.ts` exports exactly two constants (`PDF_PASSWORD_REQUIRED`,
`PDF_PASSWORD_INVALID`), and `Import.tsx:371-385` branches on exactly those two — every other code
(including the well-differentiated OCR/no-table/zero-transaction ones) falls into one generic `else`
that prints `e.response.data.message` verbatim. A network-unreachable case (no `e.response` at all) does
get its own distinct message, which the audit didn't call out as a separate branch but is worth noting
as one more piece of existing differentiation.

| Option | Cost | What it buys |
|---|---|---|
| **A. Do nothing** | Zero | Users already get reasonable prose today, because the backend authors wrote good messages into `message` even for codes the frontend doesn't branch on. This is the "it's not actually broken, just not differentiated" case |
| **B. Add client-side branches for the remaining 4-5 codes** | Small — a `switch`/lookup table keyed on `errorCode`, similar shape to the existing password branch | Differentiated UI treatment per failure type: an OCR-required failure could show a "try a text-based export instead" affordance, a no-table-found failure could suggest checking the file is really a bank statement, etc. — currently every one of these is a flat error banner |
| **C. Give the corrupt-PDF path a real `ErrorCode`** | Very small — `PdfTextExtractor.java:115-117` currently throws with no code, which also means it's indistinguishable from any other codeless failure in the server-side histogram, not just on the frontend | Closes a server-side observability gap as a side effect of a frontend fix — worth doing regardless of whether B ships, since it also improves §6 |
| **D. Differentiate the parser-crash / 500 case specifically** | Requires a decision, not just code — today's `"Unexpected error"` is deliberately generic in `GlobalExceptionHandler.java` for a 500, and that's a defensible security posture (don't leak stack traces/internals to the client). The fix here is UX, not information disclosure: a friendlier generic message plus "we've logged this, try again or contact support with reference X" rather than exposing what actually broke | Doesn't require exposing the exception; still meaningfully better than "Unexpected error" with nothing actionable |

None of B/C/D depend on the two production-config unknowns — this is the one question in the document
that's entirely decoupled from the async-queue question, since it's purely about how the frontend
handles error codes the backend already returns today, sync or async.

---

## 5. What can admin/support see, starting from a real user complaint?

**Today: nothing, for a failure.** The admin trace UI (`ImportTrace.tsx`, backed by
`AdminImportTraceController`) is real and detailed — parse outcome, per-stage timings, verification
findings, learning events, all joined in one view. But every lookup into it requires either an internal
analysis reference (`SA-YYYYMMDD-NNNN`, generated server-side and never returned to the user) or a job
id. There is no query path by user id, email, or file name anywhere in
`StatementAnalysisSessionRepository`. The one endpoint that does join a user to an import
(`AdminSystemService.recentImports`) only covers the last 20 **successful** imports — a failure never
appears there at all.

So today, "a user emails support saying their HDFC statement from yesterday failed" cannot be answered
from the product without either (a) asking the user for information the product never captured, or (b)
a database query run directly against `statement_analysis_sessions` by someone with DB access, since the
`userId`/`fileName` fields exist on the row — they're just deliberately excluded from every admin DTO
that serves the UI.

| Option | Cost | What it buys | What it doesn't |
|---|---|---|---|
| **A. Do nothing** | Zero | — | Support remains dependent on direct DB access for every failed-import complaint, which doesn't scale past the current all-engineers-have-DB-access stage the team is presumably in pre-launch |
| **B. Add `userId`/`fileName` to the admin trace DTO and a repository query by user id/email** | Small-to-medium — the fields already exist on the underlying entity (per the audit's finding that they're only excluded at the DTO/query layer, not missing from the schema), so this is a DTO change plus a new repository method plus an admin-portal search box, not new columns or a migration | Closes the "find it from a complaint" gap directly — this is the one change that turns the already-good trace UI into something support can actually use as an entry point rather than only as a drill-down once they already have a reference id |
| **C. Return the analysis reference to the user on failure** | Small | Gives the user something to quote ("my import failed, reference SA-20260812-0145") which support can paste directly into the existing by-reference lookup — a much smaller change than B, but shifts the burden onto the user remembering/copying a reference from an error toast, which is a weaker guarantee than support being able to search by something they already have (the user's email) |
| **D. B and C together** | Sum of both, but complementary not redundand | Two independent ways into the same trace — a user-supplied reference when available (fast path), a user/email search as the fallback that always works even if the user didn't capture or forward the reference | — |

**View:** B is the more durable fix of the two because it doesn't depend on the user doing anything
correctly (copying a reference, not losing the email); C is a fine complement but a weak fix on its own.
Neither option is coupled to the production-config unknowns — this is admin tooling over data that's
already being written today, on both the sync and async paths.

---

## 6. How are failures logged/correlated?

**Already solid, per the audit and confirmed on spot-check.** Every API response carries a `requestId`
from MDC; the same correlation id is persisted on both the analysis row and the job row. Server-side,
every failure — including parser crashes, thanks to the BH-028 widening of the catch clause to
`RuntimeException` — gets a `failure_code`, a truncated failure detail, a layout fingerprint (where
applicable), and a duration. This is a genuine strength of the current system, not a gap.

Two real gaps remain, both narrow and both already scoped in the companion
`data-import-intelligence-proposal.md`, not re-designed here:

- **No parser-version concept anywhere** (confirmed: zero grep hits for `parserVersion`/
  `parser_version`/`engineVersion`/`schemaVersion` across `backend/src/main`). "This was fixed in
  version X" is not a sayable sentence today. The companion proposal's §3.3 scopes a
  `parser_version` column at S effort; this document doesn't re-scope it, only flags it as directly
  relevant to failure correlation.
- **The corrupt-PDF codeless failure** (§4's option C) is as much a logging/correlation gap as a
  frontend one — a `failureCode = null` row is invisible in any histogram or dashboard built on
  `failure_code`, including the companion proposal's Import Health dashboard.

No new option set here beyond pointing at the two items above and the companion proposal — this
question is largely "already good," which is worth stating plainly rather than manufacturing false
symmetry with the weaker sections.

---

## 7. Premium/paying customers: a different failure experience, or tier-independent?

This is a genuine product-strategy fork, not a technical one — nothing in the current code
distinguishes tiers on the import path at all (no premium-flag branch anywhere in `ImportService`,
`ExtractionCheck`, or the frontend Import flow was found on spot-check), so either direction is a
build-from-scratch decision, not a "turn on what exists" one.

| Option | Argument for | Argument against |
|---|---|---|
| **A. Reliability is tier-independent — fix the pipeline for everyone, no tier branching** | A failed import is a failed import; a free user's bank statement matters to them as much as a paying user's. Segmenting reliability by tier risks the wrong optics for a finance product — "your money is only handled carefully if you pay us" is a bad message to send even implicitly. Simpler to build and reason about — one code path, one set of guarantees | Doesn't create a differentiator premium buyers can point to, if the business wants import reliability itself to be part of the pitch |
| **B. Tier-differentiated failure experience — e.g., premium gets priority queue processing, a human-reviewed retry path, or faster support-visible correlation** | Gives premium subscribers a tangible operational benefit beyond feature-gating, which can be a stronger retention lever than a locked feature — reliability-as-a-benefit is a real SaaS pattern (priority support queues, SLAs) | Requires the underlying reliability work (§1-§6) to exist *first* — there's nothing to differentiate on top of if the base pipeline still loses bytes on every sync failure. Building tier logic before the base case is fixed would mean premium users get a marginally-less-broken version of something still broken for everyone, which is a worse look than fixing it uniformly first |
| **C. Tier-differentiated in support routing only, not in the pipeline itself** | A middle path — same reliability guarantees for everyone (matches A's ethical argument), but premium complaints get faster support SLA/priority triage once §5's admin lookup exists. Keeps the "your money is handled the same either way" framing while still giving paying customers something | Requires §5 to exist before it's meaningful, and requires a support-tier/SLA concept that doesn't exist anywhere in the codebase today (confirmed no support-ticket domain exists at all, per the companion `support-help-feedback-proposal.md`'s own baseline finding) — this option is coupled to two other pieces of unbuilt infrastructure, not a standalone task |

**View:** the honest sequencing point is that A (or C, which is really "A now, defer the differentiation
question to when support tooling exists") is the only option actually available today — B requires the
base pipeline reliability work first, and it would be a strange choice to build premium-only reliability
on top of a foundation this document has just spent six sections describing as broken for everyone.
Whether to eventually pursue B or settle permanently on A is a real product-positioning call for the PM,
not something the code or this audit can answer.

---

## 8. What infrastructure is required before launch, minimum, vs. what can wait?

This question sits downstream of everything above plus the two standing pre-launch-safety gaps already
in project memory (no confirmed Postgres backup/recovery path on the current Railway plan; Sentry wired
in code but `SENTRY_DSN` unset in Railway prod, so no confirmed prod alerting) — both are infra-side
versions of the same underlying question this document asks from the import-pipeline side: is Finora
actually production-safe for real user financial data that's already live.

**Prerequisite, before any of the below can be sequenced:** answer 0(c) — the two Railway config values.
Everything in this section's cost column is conditional on that answer.

| Item | Case for "before launch" | Case for "can wait" |
|---|---|---|
| §1C / §2 — byte persistence on sync failure | The sharpest, most concrete user-facing gap identified in this whole audit: real users' bank statements today vanish on failure with no way back except re-finding and re-uploading the file. This is the closest thing to a "data loss" story in the document, even though technically nothing the user already submitted is lost — the friction is real | If launch timeline is tight and 0(c) resolves to "queue already on, storage already durable," this may already be non-work — verification, not a build |
| §3 staged-session resume UI | Cheap, backend already built and tested, zero coupling to the two unknowns | Genuinely low-severity compared to the failure case above — an abandoned staged session is a user who didn't finish, not a user who hit an error |
| §4 frontend error-code differentiation | Users already get reasonable prose via the generic branch today (per spot-check) — this is a polish gap, not a broken-experience gap | Can reasonably wait; not zero value pre-launch, but not the sharpest gap either |
| §5 admin lookup-by-user | Not user-facing at all — doesn't block a user's experience, blocks support's ability to help a user who complains | Could slip past launch if the team accepts "DB query by an engineer" as the interim support path, which is a real and currently-functional (if unscalable) fallback |
| §7 tier differentiation | Not applicable pre-launch per §7's own analysis — nothing to differentiate on top of yet | Explicitly a post-base-reliability, post-support-tooling question |
| The two standing infra gaps (backups, Sentry) | Already flagged in project memory as blocking pre-launch safety items, independent of this document — a production financial app with no confirmed backup/recovery path and no confirmed error alerting is a materially different risk category than "imports have rough edges" | N/A — these read as harder blockers than most of this document's import-specific gaps, given real user financial data is already live |

**View:** if forced to rank, the two standing infra gaps (backups, alerting) and §1C/§2 (byte loss on
failure) look like the closest things to genuine pre-launch blockers in the combined list — the former
because "no recovery path for a financial app's database" is categorically more severe than any single
import UX gap, the latter because it's the one import-pipeline gap with a real, if modest, "user's data
just vanishes" story rather than a "the experience could be nicer" one. Everything else in this document
reads as legitimate and worth doing, but sequenceable after those, not blocking them. This is one
opinion for the PM to weigh, not a scoped launch gate.

---

## Summary table — options by section, cost, and coupling to the two unknowns

| # | Question | Cheapest real option | Coupled to queue/storage unknowns? |
|---|---|---|---|
| 1/2 | Failure record + retention | §1D (record, no bytes) cheaper than §1C (record + bytes) cheaper than §1B (turn on queue) | C and B: yes. D: no |
| 3 | Retry without re-upload | Staged-session resume UI (backend already built) | No |
| 4 | Customer-visible errors | Corrupt-PDF error code (§4C) | No |
| 5 | Admin/support lookup | Add userId/fileName to trace DTO + query (§5B) | No |
| 6 | Logging/correlation | Already strong; parser-version column is the one real gap (tracked in companion proposal) | No |
| 7 | Tier differentiation | Not actionable until §1-§6 exist | Indirectly, via §1 |
| 8 | Pre-launch minimum | Depends entirely on 0(c)'s answer | Yes — this is the whole point of 0(c) |

The two items with zero coupling to the Railway unknowns — staged-session resume (§3) and frontend
error-code differentiation (§4) — are also the cheapest and most independently shippable items in this
document, regardless of what 0(c) turns out to say. Everything else genuinely needs that operational
answer before its cost can be estimated with confidence.
