# Account Ownership Intelligence — Design Proposal

**Status:** V1 (§3) approved for implementation ahead of the usual GA-blockers-first sequencing —
deliberate exception, made 2026-08-30, because V1 scoped down to a footprint (three nullable fields,
one utility function, one non-blocking dialog, no `Account`/schema risk) small enough that the
resourcing rationale behind that sequencing rule doesn't apply to it. Everything in §5 (Future/Parked)
remains unapproved and follows the normal sequencing — same as every other document in this directory.

> **Design principle.** Ownership Intelligence is a data-quality system, not an identity-verification
> system. Everything here exists to prevent accidental imports, preserve analytics correctness,
> maintain auditability, and surface uncertainty — not to prove who legally owns a bank account. This
> line is the guardrail against the feature slowly growing into pseudo-KYC, fraud scoring, or access
> control, none of which are this proposal's job, in V1 or later.
>
> **V1's acceptance test.** Success for V1 is measured by preventing accidental wrong-statement
> imports and improving analytics integrity — not by determining account ownership. Every V1
> requirement must answer yes to one question: *does this help detect an accidental upload of the
> wrong statement?* If the answer is no — ownership verification, trust scores, consent workflows,
> business-account handling, household finance — it belongs in §5, not V1, no matter how reasonable it
> sounds in isolation.

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
(grabbing the wrong PDF from a shared Downloads folder). This makes the problem a data-quality concern
before it's a security one: an incorrect ownership assumption doesn't just mis-render one dialog, it
silently pollutes net worth, cash flow, spending trends, budgeting, forecasting, and any future
AI-generated insight built on top of the same accounts, with nothing in the product able to tell the
difference. A hard "holder name ≠ profile name" block is the wrong fix regardless: it would
false-positive on every legitimate joint account, family-managed account, or accountant/business use
case, none of which are exotic for a personal finance product. **This document scopes V1 to solve
exactly that one problem — the accidental upload — as narrowly as possible, and parks everything more
ambitious for later rather than losing it.**

One question worth naming even though V1 doesn't resolve it: extraction (reading the holder name off
the statement) runs the moment a file is parsed, before the user sees anything. Whether that alone is
processing a third party's data in a way that needs its own answer is separate from what V1 does with
the result — see §6.

## 1. Identity model (governs V1 and everything parked)

Three identities must stay conceptually separate, never collapsed into one field, regardless of how
much of this ships now:

```
Workspace User        → who is logged in (Rahul)
Statement Holder       → who the statement's own text says owns the account (extracted, untrusted)
Declared Relationship  → what the user tells Finora about that account (self-reported, not verified) —
                          does not exist yet; introduced only if the Future scope (§5) is ever built
```

**V1 only ever populates the first two.** There is no ownership classification, no "who owns this
account" question asked of the user, and no third field in V1 — that's the core of keeping this small.
The three-identity shape is documented here so that if/when Declared Relationship is added later, it's
added as its own field next to the other two, never by overwriting Statement Holder with whatever gets
declared. Collapsing them would destroy the one signal — disagreement between what a statement says and
what the profile says — that even V1's warning depends on.

**One rule that matters now, not just later: nothing in V1 verifies who actually owns the account.** A
low-similarity warning is a data-quality nudge, not a finding. Continuing past it records that the user
was told and chose to proceed — it is not evidence of anything about the account itself.

## 2. What exists today (reusable, not a gap)

- `PdfMetadataExtractor` already extracts `accountHolderName` from a statement during import — V1 needs
  no new extraction work, only a new place to persist and compare it.
- `ProductIdentity` / `ProductIdentityResolver` (`imports/product/`) already match on account number and
  institution across a user's own re-imports, and already decide whether an import resolves to an
  *existing* account via an exact match (`ImportService.resolveTargetAccount`). V1 can read that
  existing result for free (§3.1) without changing how matching works.
- The real-corpus test harness (21+ unredacted bank statements across multiple banks) already exists
  and is the right place to validate V1's fuzzy-match threshold before it ships — `PdfMetadataExtractor`
  has a documented history of bank-specific holder-name recovery bugs (PNB, HDFC), so extraction quality
  is not yet a given.
- No fuzzy name-comparison utility exists anywhere in `imports`/`accounts` today — this is the one
  genuinely new piece of algorithmic logic V1 needs.

## 3. V1 — what we build now

Governed by the acceptance test at the top of this document. Everything below is what passes it.

Deliberately narrow: a data-quality safeguard and a user-awareness nudge, nothing more.

### 3.1 What it does

1. At the existing statement review step, compare the extracted `accountHolderName` against the
   logged-in user's profile name (`User.getFullName()`) using a new, small fuzzy-match utility.
   **The extracted name is untrusted input, not ground truth** — §1 already calls it that, and it's
   worth repeating right where the comparison is built: `PdfMetadataExtractor` has a documented history
   of bank-specific extraction errors, so a "mismatch" can just as easily mean a parsing mistake as a
   real ownership question. This is exactly why the result can only ever produce a non-blocking nudge,
   never a block — treating extracted text as `Extracted Name == Truth` is the mistake to design against.
2. **Skip the comparison entirely when this import already resolved to an existing account via an
   exact `ProductIdentity` match** (§2) — that continuity already vouches for the account being the
   same one imported before; re-running a name check on every routine monthly statement would be pure
   noise. This reads an already-computed result; it does not change account-matching logic.

   **Tradeoff worth stating plainly: this assumes the first import was correct.** If an account's very
   first statement was itself a mistaken upload, every subsequent statement for that same account will
   match via `ProductIdentity` and skip the review forever — the mistake becomes permanently invisible
   rather than merely unreviewed once. Acceptable for V1 (catching the wrong-file mistake on *a* later
   import is still better than never), not fully solved by it. A future ownership-review enhancement
   could periodically re-evaluate existing accounts rather than only checking on first sight; not
   designed here, just flagged so the gap is a documented tradeoff, not a discovered one.
3. **Multi-holder statements must be split before comparing, not compared as one string.**
   `PdfMetadataExtractor` (`imports/pdf/PdfMetadataExtractor.java`) populates `accountHolderName` as a
   single `String` at every extraction path today — confirmed by reading the extractor, not assumed. A
   joint statement printed as "RAHUL AND PRIYA SHARMA" or "RAHUL SHARMA & PRIYA SHARMA" lands in that
   field as one raw string; comparing it whole against "Rahul Sharma" would under-score it and fire the
   warning on the single most common legitimate case (a joint account) — exactly backwards. This is not
   new scope, it's what "compare correctly" requires: split on common joiners ("AND"/"&"/"OR") and match
   the profile name against each extracted name individually.
4. **On a strong mismatch:** show a plain, non-blocking review step with a folded-in reminder — one
   dialog, not a separate consent step:

   ```
   Statement Check
   Statement Holder:   Sunil Verma
   Finora Profile:     Rahul Sharma

   The statement holder name differs from your Finora profile name. Please confirm you've selected
   the correct statement before continuing.

   [ Continue Import ]   [ Upload Different Statement ]
   ```

   Three deliberate wording choices, all downstream of the acceptance test above:
   - **No "Ownership" in the dialog title or "verify"/"couldn't verify" in the body** — per the design
     principle, Finora isn't verifying or reviewing ownership here, and both words imply a capability
     the product doesn't have.
   - **No mention of family/joint/business/authorization.** An earlier draft of this copy said "if
     you're managing finances for a family member or joint account, you can continue — just make sure
     you're authorized to manage this account," which reads as reasonable but quietly asks the user to
     reason about *authorization*, not about *which file they uploaded* — exactly the drift the
     acceptance test exists to catch. The only question V1 is actually asking is "did you pick the
     right statement," and the copy now asks only that. Continuing is always available regardless of
     why the names differ; the copy just doesn't speculate about the reason anymore.
   - **No mention of "business"** for the same reason as before: V1 makes no business-specific
     accommodation (that's §5), so naming it would imply support that isn't there.

5. **On a strong match, or no extractable holder name:** no change to today's flow at all. This must
   not add a click, a delay, or any visible change to the common case.
6. Record what happened either way (§3.2) — not just *that* a warning fired, but *why* it did or
   didn't, so a support investigation months later doesn't start from nothing.

### 3.2 Data model (V1 only)

```
StatementImport
  + extractedHolderName     string, nullable — snapshot of what PdfMetadataExtractor saw at THIS import
  + ownershipMatchStatus    enum, nullable:
                               NAME_MATCH             — strong name similarity, no warning shown
                               NAME_MISMATCH          — low similarity, warning shown (§3.1.4)
                               NO_HOLDER_FOUND         — extraction found no holder name to compare
                               SKIPPED_EXISTING_ACCOUNT — resolved via exact ProductIdentity match (§3.1.2)
  + userConfirmedContinue   boolean, nullable — only meaningful when status = NAME_MISMATCH
```

A bare `ownershipWarningShown` boolean would only ever answer "did a warning appear" — a later "why
didn't a warning appear on this import" question would have no answer beyond re-deriving it from
scratch. `ownershipMatchStatus` costs nothing extra to capture (it's a byproduct of the one comparison
already being made) and answers both directions.

`NAME_MATCH`/`NAME_MISMATCH`, not the shorter `MATCH`/`MISMATCH`: a bare `MATCH` reads as stronger than
what actually happened (two strings compared favorably), and invites exactly the drift §1's design
principle exists to prevent — a future `if (status == MATCH) { // account owner verified }` would be
wrong the moment it's written. The name stays honest about what the field actually records.

**`ownershipMatchStatus`'s distribution is the metric that tells you whether §5 is worth building at
all** — "warnings fire on 2.3% of first-time imports" or "90% of imports skip the check via
`SKIPPED_EXISTING_ACCOUNT`" are exactly the numbers that would justify (or rule out) investing in a
confidence engine, and they fall out of this field for free. Worth tracking in aggregate once V1 ships
(this codebase already has a metrics convention for exactly this — e.g. `finora.worker.dead_letters`
in the import pipeline), not a new feature, just making sure the data V1 already stores actually gets
looked at. `NO_HOLDER_FOUND` and `SKIPPED_EXISTING_ACCOUNT` earn their place on that basis alone —
they're analytics/debugging states, not something a user ever needs a name for.

These fields (`extractedHolderName`, `ownershipMatchStatus`, `userConfirmedContinue`) are for
debugging, analytics-quality investigation, and future product evaluation. **None of them are surfaced
to end users in V1** — the only user-facing surface is the warning dialog itself (§3.1.4), which reads
`ownershipMatchStatus` but never displays it.

Nothing changes on `Account`. No new table, no new relationships. This is the entire schema footprint
of V1: one new string field, one new enum, one new boolean, all nullable.

### 3.3 Explicit V1 non-goals

- **No hard blocks.** The user can always continue.
- **No ownership verification claims.** A "continue" click proves nothing about who owns the account;
  see §1's rule.
- **No KYC implications.** No identity document, no verified-authorization record, no compliance
  control — the folded-in reminder in §3.1 is copy, not a mechanism.
- **No changes to existing account-matching logic.** §3.1's skip-check reads `ProductIdentityResolver`'s
  existing output; nothing about how accounts are matched or created changes.
- **No ownership classification, no declared relationship, no reporting split.** All of §5.

## 4. Migration (V1's own fields only)

V1 adds three new, nullable fields to `StatementImport` (a string, an enum, and a boolean — see §3.2),
populated only going forward. Historical import
rows simply have `extractedHolderName = null`, which the comparison logic already treats as "nothing to
check" (§3.1, point 5) — no backfill, no retroactive computation, nothing to design here. The more
interesting migration question — what happens to *accounts* imported before any ownership classification
existed — only applies to §5's parked scope and is addressed there if that scope is ever picked up.

## 5. Future / Parked ideas (not V1)

Nothing below is removed from the vision — it's deliberately kept out of V1's implementation effort,
schema, UX surface, test matrix, and support burden until there's real usage data (from V1 itself) to
design it against, and until GA/bug-hunt priorities allow room for it.

**Ownership classification & reporting**
- Declared ownership type on `Account` (`SELF` / `FAMILY_MEMBER` / `JOINT` / `BUSINESS` / `OTHER`),
  self-reported and never treated as verified.
- A further split of `BUSINESS` into **owned** vs. **managed** (owner vs. accountant/employee/other
  access) — "Rahul owns ABC Pvt Ltd" and "Rahul is ABC's accountant" are different situations a single
  `BUSINESS` bucket flattens; worth the distinction whenever this ships, cheap to add then.
- `Personal Assets` vs. `Managed Assets` net-worth split, built on the declared type above.

**Confidence & explainability**
- A real confidence engine, ranking signals rather than a single name comparison: account/product
  identity continuity (strongest — already exists, see §2) above historical holder-name consistency
  (needs `extractedHolderName` history to accumulate first, which V1 starts collecting) above
  human-readable name similarity (weakest — the only signal V1 uses, and only because nothing stronger
  is available yet for a first-time import).
- An explainability UI showing *why* a confidence value is what it is — persisting structured signals
  (`nameMatch`, `firstImportOfHolder`, `recentProfileNameChange`, ...) rather than a bare score, so it
  can be explained, audited, and re-tuned without losing history. Mirrors the shape
  `ReconciliationExplanation` already uses elsewhere in this codebase for the same reason.
- A structured `ownershipReviewReason` field (`LOW_NAME_MATCH`, `FIRST_SEEN_HOLDER`,
  `PROFILE_RECENTLY_RENAMED`, `MULTIPLE_HOLDERS`, ...) once there's more than one possible reason for a
  review to fire — V1 only ever has one reason, so the field adds nothing yet.
- Trust badges surfaced in the product — only once the underlying signal is validated against the real
  bank-statement corpus; a badge is a claim of certainty extraction doesn't yet back up.

**Anomaly detection**
- Behavioral anomaly detection ("this import differs from your last 5") — needs the import history
  V1 starts recording, doesn't have it yet.

**Household finance**
- Multi-entity ownership relationships (spouse, child, parent, joint holder) and a household-level
  net-worth rollup. A genuinely large, separate data-model change — worth its own proposal once §5's
  declared-type data (once built) shows real usage patterns to design around.

**Identity / consent verification**
- Any actual identity verification or KYC — not needed for a personal finance tracker where all data is
  user-provided by definition. Becomes a real requirement only if Finora ever becomes decision-bearing
  infrastructure (lending, credit scoring).
- A separate, trackable "I confirm I am authorized to manage this account" acknowledgment (its own
  persisted field, not just folded warning copy) — worth doing once/if this needs to be more than a
  reminder, but that's a step toward the identity-verification territory §1's design principle
  deliberately keeps V1 out of.

## 6. Known limitations that apply even to V1

- **A user could rename their own Finora profile to match a statement's holder name to suppress the
  warning.** If §5's confidence engine is ever built, a recent profile-name change should reduce
  confidence rather than being ignored — noted here so it isn't rediscovered as a surprise later. V1
  has no mitigation for this and isn't expected to.
- **Retention of a third party's PII on an abandoned import** (user sees the §3.1 warning and clicks
  "Upload Different Statement" without continuing) needs an explicit answer before V1 ships — does
  `extractedHolderName`/staged extraction data from the abandoned attempt get cleaned up, or persist
  indefinitely? This is a real V1 question, not a Future one, since V1 itself introduces the retained
  field.
- **Compliance framing.** This product's user base is Indian bank statements — the relevant regime is
  India's DPDP Act (2023), not GDPR. Processing financial data relating to another individual may have
  implications under that Act. **These observations are recorded for future consideration. V1
  introduces no compliance workflow, no consent workflow, no legal-review gate, and no user-facing
  policy change** — the sentence above is context for whoever eventually builds §5's more ambitious
  ideas, not a requirement or blocker for the warning dialog in §3. Legal review is advisable before
  building any future feature that explicitly models or monetizes multi-person financial relationships
  (§5's household finance, in particular) — not before V1.

## 7. Estimated effort

| Component | Effort |
|---|---|
| Data model (§3.2 — three new nullable fields on one existing table) | S |
| Fuzzy holder-name-match utility, multi-holder-aware (§3.1) | S |
| Ownership review step UI (§3.1) | S |
| ~~Everything in §5~~ | Deferred — not estimated |

## 8. Open questions for whoever implements V1

- Exact fuzzy-match threshold and algorithm (token overlap vs. edit distance vs. both) — needs
  validation against the real bank-statement corpus's actual holder-name variance (`R Sharma` vs.
  `Rahul Sharma` vs. `Rahul Kumar Sharma`), not chosen abstractly.
- Multi-holder splitting rules (§3.1) — which joiners to treat as holder separators ("AND"/"&"/"OR"/
  comma), validated against how joint accounts actually print in the real corpus, not assumed.
- Abandoned-import PII retention policy (§6) — needs an answer before V1 ships, not after.
