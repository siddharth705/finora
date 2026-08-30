# Account Ownership Intelligence — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

> **Design principle.** Ownership Intelligence is a data-quality system, not an identity-verification
> system. Everything here exists to prevent accidental imports, preserve analytics correctness,
> maintain auditability, and surface uncertainty — not to prove who legally owns a bank account. This
> line is the guardrail against the feature slowly growing into pseudo-KYC, fraud scoring, or access
> control, none of which are this proposal's job.

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

**There is a sharper question underneath "do we retain this data" (§6): do we process a third party's
personal financial data at all, before the uploading user has confirmed anything?** Extraction (name,
account number, transaction list) runs the moment a file is parsed — before §3.2's review step ever
renders. Retention policy governs what happens after that point; it doesn't address the fact that
processing itself already happened. This is a product/legal question in its own right, surfaced here
so it isn't lost inside an "open question" at the bottom of the document (see §6).

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

**Two non-negotiable rules follow from that separation, and both belong in the architecture, not just
this document:**

1. **A declared ownership type is metadata, never a verified fact.** Nothing in the product may treat
   `declaredOwnershipType = SELF` as proof an account belongs to the workspace user, and nothing may
   treat `FAMILY_MEMBER`/`BUSINESS`/etc. as proof it doesn't. It is exactly as reliable as any other
   self-reported field in this product (which is to say: not verified, and not meant to be).
2. **Future analytics, forecasting, and AI-generated insight must not assume an imported account
   belongs to the workspace owner.** This is the actual stakes of the whole proposal: a wrongly-scoped
   account doesn't just mis-render one warning dialog, it silently corrupts net worth, spending trends,
   budgets, and every insight built on top of them, with no way for the product to know it happened.
   Ownership metadata (once it exists) is the input every analytics/AI feature must consult before
   attributing an account's numbers to "the user," not an optional enrichment layered on afterward.

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
  + declaredOwnershipType   enum: SELF, FAMILY_MEMBER, JOINT, BUSINESS_OWNED, BUSINESS_MANAGED,
                             OTHER, nullable
  + declaredHolderName      string, nullable, user-editable label

StatementImport
  + extractedHolderName     string, nullable — snapshot of what PdfMetadataExtractor saw at THIS import
  + ownershipWarningShown   boolean
  + ownershipReviewReason   enum, nullable: LOW_NAME_MATCH, FIRST_SEEN_HOLDER,
                             PROFILE_RECENTLY_RENAMED, MULTIPLE_HOLDERS
  + userConfirmedContinue   boolean
```

`ownershipReviewReason` is what makes this explainable to support later — "why did this warning fire"
otherwise has no answer beyond re-deriving it from scratch. Structured, not a free-text note, for the
same reason `ErrorCode`/`ReconciliationExplanation` are structured elsewhere in this codebase: support
tooling and any future analytics on warning frequency both need to group on it.

`declaredOwnershipType`/`declaredHolderName` live on `Account`, not per-import: ownership is a property
of the account relationship over its lifetime, not re-declared on every statement. `StatementImport`
carries the point-in-time audit facts (Level 8 in the originating draft) — cheap to add now, impossible
to backfill later once historical imports exist without it.

**Explicit design correction to the originating draft:** do not persist a bare confidence score as a
column. If a confidence computation is ever built (§4, deferred), persist the structured signals it was
computed from (`nameMatch`, `firstImportOfHolder`, `recentProfileNameChange`, ...) and derive the score
at read time — matching `ReconciliationExplanation`'s existing shape. A persisted score with no
underlying evidence is exactly the failure mode the originating draft's own manipulation analysis
("confidence score gaming") warns about: it can't be explained, audited, or re-tuned without losing
history.

### 3.2 Non-blocking ownership review (v1)

**Signal ranking — name similarity is the weakest signal, and the review step must be built that way,
not name-match-first:**

1. **Strongest: account/product identity continuity.** `ProductIdentity`/`ProductIdentityResolver`
   already match on account number and institution across a user's own re-imports (§2) — if this
   statement's account number matches an account the user has imported before, that outranks anything
   the holder-name text says, full stop.
2. **Medium: historical holder-name consistency.** Does this import's `extractedHolderName` match what
   previous imports for this same account recorded? (Only meaningful once §3.1's snapshot field has
   history to compare against — see §5 for what happens before that history exists.)
3. **Weakest: human-readable name similarity against the profile.** Only consulted when the stronger
   signals above are unavailable or inconclusive (typically: first-ever import of a given account, no
   account-number match to lean on). This is the one implemented in v1 (§1 and §2 don't have enough
   history yet), using a new small fuzzy-match utility (token overlap / edit-distance on
   `extractedHolderName` vs. `User.getFullName()`) — but it must be built and treated as the fallback
   tier, not the primary check, so it's structurally ready to be demoted once §4's stronger signals
   exist.

**Multi-holder statements are a real, verified gap, not a hypothetical.** `PdfMetadataExtractor`
(`imports/pdf/PdfMetadataExtractor.java`) populates `accountHolderName` as a single `String` at every
extraction path (regex match, leading-line fallback) — confirmed by reading the extractor, not
assumed. A joint statement printed as "RAHUL AND PRIYA SHARMA" or "RAHUL SHARMA & PRIYA SHARMA" lands
in that field as one raw string. A naive matcher comparing that whole string against "Rahul Sharma"
would under-score it — which means the single most legitimate case this feature exists to accommodate
(a joint account) would trigger the warning most often, exactly backwards. **The v1 matcher must be
multi-holder aware** (split on common joiners — "AND"/"&"/"OR" — and match the profile name against
each extracted name individually, not the concatenated string) before this ships, not as a follow-up.

- **High similarity (against any extracted holder, per above) or no extractable holder name:** no
  change to today's flow. This must not add a click to the common case.
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
settings, offer: **Me / Family Member / Joint Account / Business I Own / Business I Manage / Other.**
Defaults to `SELF` silently whenever the name match is high — this must stay optional metadata capture,
never a mandatory step on every import.

`BUSINESS_OWNED` and `BUSINESS_MANAGED` are separate values, not one `BUSINESS` bucket: "Rahul owns ABC
Pvt Ltd," "Rahul is ABC's accountant," and "Rahul obtained ABC's statement somehow" are meaningfully
different situations that a single `BUSINESS` value would flatten into indistinguishable data. Splitting
them costs nothing extra here (two enum values instead of one) — the UI can still present them as a
single "Business" choice with a follow-up sub-question if that reads better, but the stored value must
keep the distinction from day one, since collapsing it now and trying to recover it from historical data
later isn't possible.

This is the smallest piece of the proposal with standalone product value independent of everything
else here: a `Personal Assets` vs. `Managed Assets` split on net worth becomes possible immediately
once even a handful of accounts carry a declared type, and it's a genuinely differentiated feature few
consumer finance apps in this market do well. Per §1's rule 1, this split must always be presented as
"assets you've told Finora you manage," never as a verified ownership claim.

### 3.4 Privacy nudge (v1, paired with §3.2)

Shown only alongside the low-confidence review step, never on its own:

> "This statement contains financial information belonging to another individual. Please ensure you
> are authorized to manage or import this account."

This is a consent reminder and mistake-prevention device, explicitly **not** a legal authorization
mechanism or compliance control — it verifies nothing. Track it as UX/liability-reduction, not as a
KYC feature, when this gets estimated and reviewed. It also does not resolve §0's processing-before-
consent question — by the time this nudge renders, extraction has already run once; this is damage
control for imports the user chooses to continue, not a gate on processing itself.

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

## 5. Historical imports (migration behavior)

This proposal's fields don't exist on any statement imported before it ships — every user who's already
uploaded statements has accounts with no `extractedHolderName` snapshot and no `declaredOwnershipType`.
Three options, evaluated rather than picked abstractly:

- **A — leave historical rows unset, compute nothing retroactively.** Every pre-existing account simply
  has `declaredOwnershipType = null` until the user (or a future import) sets it. Safe, but a large
  number of "unreviewed" accounts on day one.
- **B — run ownership matching retrospectively** against historical data. Risky: it would render a
  judgment (e.g. "low confidence") about an import the user made before this feature existed and never
  had a chance to review at the time, based on data the extraction pipeline didn't capture consistently
  before now (see below).
- **C — lazy evaluation**, computing ownership signals only when an account is viewed.

**Recommendation: Option A**, not C. The reasoning is structural, not a preference: §3.2's signal
ranking depends on `extractedHolderName` as a point-in-time snapshot (§3.1), and that field simply
doesn't exist for historical `StatementImport` rows — there is nothing to lazily evaluate without
re-parsing original statement content, which is a separate, larger capability this proposal doesn't
scope. This exact "we don't know what happened before this field existed" situation already has a
precedent in this codebase: `StatementImport.BalanceApplicationMode.UNKNOWN_LEGACY` (added for
statements that predate that field) takes no automatic action and states plainly that guessing would
risk silently corrupting data, rather than inferring a legacy row's behavior after the fact. Option A
is the same discipline applied here — leave historical accounts unset, and let §3.2's review step
populate real data going forward, the same way `UNKNOWN_LEGACY` rows get resolved by an administrator
choosing to look, not by the system guessing.

## 6. Known limitations to carry forward, not solve now

- **Self-declaration can't be verified** — governed by §1's rule 1, not restated here.
- **A user could rename their own Finora profile to match a statement's holder name to suppress the
  warning.** If §4's confidence engine is ever built, a recent profile-name change should reduce
  confidence rather than being ignored — noted here so it isn't rediscovered as a surprise later.
- **Retention of a third party's PII on an abandoned import** (user sees the §3.2 warning and clicks
  "Upload Different Statement" without continuing) needs an explicit answer before this ships — does
  `extractedHolderName`/staged extraction data from the abandoned attempt get cleaned up, or does it
  persist indefinitely from the staging pass? This sits alongside §0's larger processing-before-consent
  question, not as a substitute for it — retention policy and whether processing should happen at all
  are two separate answers this proposal needs before implementation, not one.
- **Compliance framing.** This product's user base is Indian bank statements — the relevant regime is
  India's DPDP Act (2023), not GDPR. Processing another individual's financial data inside a different
  person's account, without that individual's consent, is exactly the kind of processing that regime
  is concerned with. Not a blocker for the non-blocking §3.2–§3.4 scope, but worth explicit legal input
  before any monetized feature (§4's household finance, in particular) is built on top of it.

## 7. Estimated effort

| Component | Effort |
|---|---|
| Data model (§3.1, incl. `ownershipReviewReason`) | S |
| Fuzzy holder-name-match utility, multi-holder-aware (§3.2 — larger than a plain string compare) | S–M |
| Ownership review step + warning UI (§3.2, §3.4) | S–M |
| Declared ownership type picker (six values) + Personal/Managed Assets split (§3.3) | M |
| ~~Confidence engine, badges, anomaly detection, household finance~~ | Deferred — not estimated |

## 8. Open questions for whoever implements this

- Exact fuzzy-match threshold and algorithm (token overlap vs. edit distance vs. both) — needs
  validation against the real bank-statement corpus's actual holder-name variance (`R Sharma` vs.
  `Rahul Sharma` vs. `Rahul Kumar Sharma`), not chosen abstractly.
- Multi-holder splitting rules (§3.2) — which joiners to treat as holder separators ("AND"/"&"/"OR"/
  comma), validated against how joint accounts actually print in the real corpus, not assumed.
- Does extraction (parsing, running `PdfMetadataExtractor`) need to be gated on anything before it
  runs at all, or is retention (§6) the only lever available — this is §0's processing-before-consent
  question and needs a real answer, not just a flagged one, before implementation.
- Whether `declaredOwnershipType` should be settable/editable outside the import flow (account
  settings) from day one, or only captured reactively when §3.2's warning fires.
