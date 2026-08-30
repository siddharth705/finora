# Account Ownership Intelligence — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

## 0. The problem

Today, a logged-in user can upload literally any bank statement — including one printed with someone
else's name and account number — and Finora accepts it without comment. Verified directly against the
current code, not inferred:

- Ownership checks at confirm time verify the user owns the *target Finora `Account` row*
  (`OwnershipGuard.requireOwned`, `ImportService.resolveTargetAccount`), never that the statement's
  printed holder matches the user's own identity.
- `PdfMetadataExtractor` already extracts an `accountHolderName` from the statement, but it is used
  only for cross-statement account matching (`ProductIdentity`'s weak-signal fallback) — it is never
  compared against `User.getFullName()` anywhere in the codebase.
- Duplicate detection (`DuplicateDetector`, `ReconciliationService.reconcileForUser`/`reconcileForImport`)
  is strictly scoped per-user (`findByUserId`). Two different users uploading the identical statement
  file are invisible to each other's duplicate check.
- `NewAccountRequest`'s `name`/`accountHolderName` fields are free client-supplied text, not validated
  against extraction output.

No cross-account data leak exists (account matching stays scoped to the uploader's own accounts, so
this can't merge into or expose a different user's *own* Finora account) — this is a one-way
data-ingestion gap, not an isolation bug. But it means Finora currently treats "I own this Finora
account" and "this statement belongs to me" as the same fact, when they aren't.

**The dominant real-world case is not malicious impersonation — it's an accidental wrong-file upload**
(grabbing the wrong PDF from a shared Downloads folder). That case alone silently pollutes net worth,
cash flow, spending trends, budgets, and any future AI-generated insight, with the product having no
way to know it happened. A hard "holder name ≠ profile name" block is the wrong fix regardless: it
would false-positive on every legitimate joint account, family-managed account, or accountant/business
use case, none of which are exotic for a personal finance product.

## 1. Objective

Give Finora a way to reason about, surface, and record account ownership — without ever hard-blocking
an import on an unreliable signal. Three identities must stay separate in the data model and never be
collapsed into one field:

```
Workspace User        → who is logged in (Rahul)
Statement Holder       → who the statement's own text says owns the account (extracted, untrusted)
Declared Relationship  → what the user tells Finora about that account (self-reported, not verified)
```

Collapsing these (e.g. overwriting extracted holder name with what the user later declares) destroys
the audit trail and removes the one signal — disagreement between the three — that makes this feature
useful at all.

## 2. What exists today (reusable, not a gap)

- `ProductIdentity` / `ProductIdentityResolver` (`imports/product/`) already extract and match on
  holder name, institution, and account number across a user's own re-imports — the natural home to
  extend for a "historical consistency" signal, not new infrastructure.
- `ReconciliationExplanation` (used for duplicate/transfer/refund flagging) already stores structured
  evidence — a map of what was compared and how — rather than a bare verdict. This is the right shape
  to reuse for ownership signals too; a future confidence score must never be the only thing persisted.
- `AuditService.record(...)` is the established pattern for "what happened and why, queryable later by
  support" across this codebase. Ownership decisions belong here, not in a new subsystem.
- The real-corpus test harness (21+ unredacted bank statements across multiple banks) already exists
  and is the right place to measure holder-name extraction accuracy before any confidence number is
  ever shown to a user — `PdfMetadataExtractor` has a documented history of bank-specific holder-name
  recovery bugs (PNB, HDFC), so extraction quality is not yet a given.
- No fuzzy name-comparison utility exists anywhere in `imports`/`accounts` — this is the one genuinely
  new piece of algorithmic logic this proposal needs, not glue code over something existing.

## 3. Proposed scope

### 3.1 Data model (v1 — ships invisibly, unlocks everything after it)

```
Account
  + declaredOwnershipType   enum: SELF, FAMILY_MEMBER, JOINT, BUSINESS, OTHER, nullable
  + declaredHolderName      string, nullable, user-editable label

StatementImport
  + extractedHolderName     string, nullable — snapshot of what PdfMetadataExtractor saw at THIS import
  + ownershipWarningShown   boolean
  + userConfirmedContinue   boolean
```

`declaredOwnershipType`/`declaredHolderName` live on `Account`, not per-import: ownership is a property
of the account relationship over its lifetime, not re-declared on every statement. `StatementImport`
carries the point-in-time audit facts (Level 8 in the originating draft) — cheap to add now, impossible
to backfill later once historical imports exist without it.

**Explicit design correction to the originating draft:** do not persist a bare confidence score as a
column. If a confidence computation is ever built (§4, deferred), persist the structured signals it was
computed from (`nameMatch`, `firstImportOfHolder`, `recentProfileNameChange`, ...) and derive the score
at read time — matching `ReconciliationExplanation`'s existing shape. A persisted score with no
underlying evidence is exactly the failure mode the draft's own manipulation analysis (§6, "confidence
score gaming") warns about: it can't be explained, audited, or re-tuned without losing history.

### 3.2 Non-blocking ownership review (v1)

At the existing statement review screen (where `DetectedAccountInfo` already surfaces extracted
metadata), add a name-comparison step using a new small fuzzy-match utility (token overlap /
edit-distance on `extractedHolderName` vs. `User.getFullName()`):

- **High similarity or no extractable holder name:** no change to today's flow. This must not add a
  click to the common case.
- **Low similarity:** show a plain, non-accusatory review step —

  ```
  Account Ownership Review
  Statement Holder:   Sunil Verma
  Finora Profile:     Rahul Sharma

  We couldn't verify this statement belongs to your Finora profile. If you're managing finances
  for a family member, business, or joint account, you can continue.

  [ Continue Import ]   [ Upload Different Statement ]
  ```

  Neither action is a dead end, and `ownershipWarningShown`/`userConfirmedContinue` record what
  happened either way.

### 3.3 Declared ownership type (v1)

When ownership confidence is low (§3.2 fires) or the user chooses to set it manually from account
settings, offer: **Me / Family Member / Joint Account / Business Account / Other.** Defaults to `SELF`
silently whenever the name match is high — this must stay optional metadata capture, never a mandatory
step on every import.

This is the smallest piece of the proposal with standalone product value independent of everything
else here: a `Personal Assets` vs. `Managed Assets` split on net worth becomes possible immediately
once even a handful of accounts carry a declared type, and it's a genuinely differentiated feature few
consumer finance apps in this market do well.

### 3.4 Privacy nudge (v1, paired with §3.2)

Shown only alongside the low-confidence review step, never on its own:

> "This statement contains financial information belonging to another individual. Please ensure you
> are authorized to manage or import this account."

This is a consent reminder and mistake-prevention device, explicitly **not** a legal authorization
mechanism or compliance control — it verifies nothing. Track it as UX/liability-reduction, not as a
KYC feature, when this gets estimated and reviewed.

## 4. Explicitly deferred (not designed further here)

- **Ownership confidence engine / explainability UI** (originating draft's Levels 3–4). Blocked on
  §3.1–§3.3 actually shipping and accumulating real declared-ownership data to compute against —
  building a scoring model before that data exists means guessing weights with no ground truth.
- **Behavioral anomaly detection** ("this differs from your last 5 imports") — same dependency: needs
  a history of declared/extracted ownership to compare against, which doesn't exist yet.
- **Trust badges throughout the product** — until §3.2's core signal (name-match confidence) is
  validated against the real bank-statement corpus, a badge is a claim of certainty the extraction
  pipeline doesn't yet back up. Revisit once accuracy is measured, and scope badges to the account
  list/detail view only, not Dashboard-wide, even then.
- **Household finance model** (multiple financial relationships per user, household net-worth rollup)
  — a genuinely large, separate data-model change (effectively multi-entity ownership, not a field on
  `Account`). Worth its own proposal once §3.3's declared-type data shows real usage patterns to design
  around, not designed blind now.
- **Any actual identity verification / KYC** — not needed for a personal finance tracker where all data
  is user-provided by definition. Becomes a real requirement only if Finora ever becomes
  decision-bearing infrastructure (lending, credit scoring) — flagged so it isn't lost, not scoped now.

## 5. Known limitations to carry forward, not solve now

- **Self-declaration can't be verified.** A user can declare `SELF` for an account that isn't theirs.
  Mitigation is structural, not enforcement: never let a declared type overwrite the extracted holder
  name (§1's identity separation), so the disagreement stays visible in the data even if the UI stops
  surfacing it.
- **A user could rename their own Finora profile to match a statement's holder name to suppress the
  warning.** If §4's confidence engine is ever built, a recent profile-name change should reduce
  confidence rather than being ignored — noted here so it isn't rediscovered as a surprise later.
- **Retention of a third party's PII on an abandoned import** (user sees the §3.2 warning and clicks
  "Upload Different Statement" without continuing) needs an explicit answer before this ships — does
  `extractedHolderName`/staged extraction data from the abandoned attempt get cleaned up, or does it
  persist indefinitely from the staging pass? Not designed here; must be answered before implementation.
- **Compliance framing.** This product's user base is Indian bank statements — the relevant regime is
  India's DPDP Act (2023), not GDPR. Processing another individual's financial data inside a different
  person's account, without that individual's consent, is exactly the kind of processing that regime
  is concerned with. Not a blocker for the non-blocking §3.2–§3.4 scope, but worth explicit legal input
  before any monetized feature (§4's household finance, in particular) is built on top of it.

## 6. Estimated effort

| Component | Effort |
|---|---|
| Data model (§3.1) | S |
| Fuzzy holder-name-match utility (new) | S |
| Ownership review step + warning UI (§3.2, §3.4) | S–M |
| Declared ownership type picker + Personal/Managed Assets split (§3.3) | M |
| ~~Confidence engine, badges, anomaly detection, household finance~~ | Deferred — not estimated |

## 7. Open questions for whoever implements this

- Exact fuzzy-match threshold and algorithm (token overlap vs. edit distance vs. both) — needs
  validation against the real bank-statement corpus's actual holder-name variance (`R Sharma` vs.
  `Rahul Sharma` vs. `Rahul Kumar Sharma`), not chosen abstractly.
- Abandoned-import PII retention policy (§5) — needs an answer before §3.2 ships, not after.
- Whether `declaredOwnershipType` should be settable/editable outside the import flow (account
  settings) from day one, or only captured reactively when §3.2's warning fires.
