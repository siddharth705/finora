# Statement Continuity & Coverage Integrity — Proposal

**Status:** proposal — not scheduled, prioritized, or approved. No code in this document, per request.

**Relationship to other proposals:** this document is a sibling to
[`reconciliation-evolution-roadmap-proposal.md`](reconciliation-evolution-roadmap-proposal.md) ("the
roadmap doc" below), not a replacement or a re-litigation of it. That document already ships or designs
several pieces this proposal builds on directly: credit-card statement fields on `StatementImport`
(shipped, PR [#451](https://github.com/siddharth705/finora/pull/451)/[#453](https://github.com/siddharth705/finora/pull/453)),
a source-trust ranking (`SourceTrust`, shipped), a confidence-scoring philosophy (Part 5), an
explainability API design (Part 8), and a founder operations dashboard with an "Import Explorer" (Part
9, planned). Where this proposal's design leans on one of those, it says so and does not repeat the
reasoning. Where it disagrees with something the roadmap doc implies, it says that explicitly too (see
§4).

**What this proposal is not:** it is not about credit-card billing-cycle *settlement* (Part 4 of the
roadmap doc — matching a payment to the spend it settles). It is not about cross-source duplicate
transactions (`ReconciliationService`, already handled at the row level). It is about a gap in both:
**nothing today knows whether the statements a user has imported for an account form a continuous,
gap-free, non-overlapping timeline** — a structurally different question from "are these rows correct"
or "does this payment settle that spend."

---

## 0. Addendum — questions resolved across two rounds of founder review

Added after two review passes. Read this before §1 — §0.1–§0.5 are round one (a real defect in §6's
original overlap rule, plus three open questions resolved); §0.6–§0.10 are round two (balance authority
for superseded statements, acknowledgment lifecycle, a correction to round one's own severity suggestion,
API summary fields, and a new risk named for the first time). The original sections below are left intact
rather than silently rewritten; each affected section carries a short pointer back here.

### 0.1 Exact adjacency semantics — and a real bug in §6's original overlap rule

Two bank-provided conventions exist for how consecutive statement periods relate to each other, and both
are real, not hypothetical:

- **Exclusive-adjacent** (the common case): period N ends on day X, period N+1 begins on day X+1 — e.g.
  May 1–31, then June 1–30.
- **Inclusive-shared-boundary**: `OpeningBalanceCarryForward`'s own class comment documents a real PNB
  pair — `31-05-2026 to 30-06-2026`, then `30-06-2026 to 31-07-2026`. The boundary date (30-06) is
  reprinted as *both* the prior period's close and the next period's open. This is not a data error; it's
  how PNB prints statements, and it's the entire reason `OpeningBalanceCarryForward` exists.

§6's original overlap rule (`A.start ≤ B.end && B.start ≤ A.end`) would flag *every* PNB statement pair as
a one-day overlap — a false positive on ordinary, correct, back-to-back imports, every month, on every
PNB account. That's a real defect the review question surfaced, not a hypothetical edge case.

**Corrected definition**, `A` = earlier segment, `B` = the next segment, same account:

```
sharedDays = A.periodEnd >= B.periodStart ? (A.periodEnd − B.periodStart + 1 day) : 0

isAdjacent(A, B):
    (sharedDays == 0 && B.periodStart == A.periodEnd.plusDays(1))   -- exclusive-adjacent
    || sharedDays == 1                                               -- single reprinted boundary day
    -> continuous: no gap, no overlap warning

isGap(A, B):     B.periodStart > A.periodEnd.plusDays(1)
    gapStart = A.periodEnd.plusDays(1)
    gapEnd   = B.periodStart.minusDays(1)

isOverlap(A, B): sharedDays >= 2
    -- more than the one known boundary-reprint day is genuinely double-covered
```

A single shared day is classified as continuous, not flagged — a known, already-handled printing
convention, not a coverage problem. Only two or more shared days are a genuine overlap. This generalizes
to credit-card cycles for free: `15 Jun–14 Jul`, then `15 Jul–14 Aug` satisfies exclusive-adjacent exactly
(14 Jul + 1 day = 15 Jul), no CC-specific branch required. The review's concern that this needs
"account-type awareness" turns out not to hold — it needs correct interval arithmetic on the printed
dates, which is already type-agnostic.

Left open: whether every bank that reprints a boundary day does so *consistently* across all its
statement pairs, or only sometimes — the class comment documents PNB specifically; other banks in
Finora's supported set haven't been checked. Recommend treating `sharedDays == 1` as continuous
universally (bank-agnostic) rather than a PNB-specific carve-out — safe either way, since misclassifying a
genuine one-day overlap as continuous only costs a missed warning, never a wrong balance (balance
correctness is `ClosingBalanceGuard`/`OpeningBalanceCarryForward`'s job, not this one's).

*(Corrects §6's overlap rule as originally written.)*

### 0.2 Annual / custom-period statements — investigated, confirmed unresolved at the product level

Checked directly rather than left as a guess: `StatementImport` has no period-type field,
`PdfMetadataExtractor` extracts *that* a period exists but never *what kind*, and no bank-specific parser
config expresses a period type anywhere. This isn't a documentation gap — **the concept doesn't exist in
the codebase at all.** Finora parses whatever start/end dates a PDF prints, monthly or otherwise, with no
way today to know which it got.

This can't be resolved by more code-reading — it's a product question (does Finora intend to support
annual/YTD imports, ever?) only Sid can answer, and the original draft was right to flag it as
unresolved rather than guess. What the design can do without that answer: degrade safely. If a segment's
printed period exceeds a generous threshold (e.g. >45 days — comfortably past any monthly or CC-cycle
length, including a long first or short last partial period), don't silently treat it as a same-shape
monthly segment for gap/overlap math. Flag it distinctly ("this statement covers an unusually long
period — 214 days") and exclude it from automatic gap/overlap classification against the normal-length
segments around it, rather than producing a confidently wrong verdict. This turns an unanswered product
question into a safe default rather than a blocker — Phase 1 can ship without Sid resolving it first, at
the cost of a less complete answer for whatever population (currently unknown, possibly zero) would
actually trigger it.

*(Resolves the open question originally left in §7.)*

### 0.3 Statement supersession / replacement — a real, separate gap, confirmed absent

Investigated directly: today, importing a second statement for an account+period that already has one
creates an entirely new, independent `StatementImport` row. `StatementImportService.confirmReimport`
calls the exact same `ImportService.confirm(...)` path as a first-time import — no existing-period check
exists anywhere in `ImportService` or `StatementImportRepository`. Nothing marks either row authoritative
or stale; both simply coexist. `ReconciliationService`'s transaction-level duplicate detection *happens*
to catch the resulting duplicate transactions when they're byte-identical
(`accountId|txnDate|amount|description`), but draws no connection at the statement level, and would miss
a genuinely corrected re-upload where amounts, descriptions, or dates differ even slightly — exactly the
"bank regenerated it" / "parser improved" case the review raised.

This is real and distinct from gap/overlap detection, not a variant of it: gap and overlap ask whether
*periods* line up; supersession asks whether *two statements claiming the same period* should be treated
as one evolving fact or two competing ones. Recommend as an explicit Phase 1 addition — a natural sibling
to the exact-duplicate-period case already in §6, since both need the same underlying check (does a
statement already exist for this exact period?). When a new statement's period exactly matches an
existing one for the same account, surface it distinctly: *"You already have a statement for this
period, imported on [date] with N transactions. Import this one as a replacement?"* If confirmed, mark
the prior `StatementImport` superseded (a new nullable `supersededBy` FK, mirroring the roadmap doc's own
"loser stays as provenance, not deleted" pattern for canonical transactions) rather than leaving two live,
competing statements with no relationship between them. A superseded statement's transactions stop
counting toward balances/Insights, the same way a `TRANSFER`-classified transaction already stops
counting toward expense totals — reused precedent, not a new mechanism.

This was missing from the original proposal's scope entirely, not a deprioritized nice-to-have — recommend
folding it into Phase 1 alongside exact-duplicate-period detection, since shipping one without the other
leaves half the problem solved.

*(A genuine addition — the original proposal did not address this case.)*

### 0.4 CSV prevalence — measurable today, and cheap to answer directly

Good news: this doesn't need investigation, it needs one query. `StatementImport.sourceFormat`
(`"CSV"`/`"PDF"`, not-null, added by `V36__statement_import_source_format.sql`) already exists on every
row — nothing currently aggregates it, but the data is there:

```sql
SELECT source_format, count(*) FROM statement_imports GROUP BY source_format;
```

Recommend running this before locking CSV support to Phase 4, rather than debating it further in the
abstract. If CSV is a small minority of imports, Phase 4's placement stands as originally proposed; if
it's a large fraction, CSV's estimated-period mechanism (§7) should move earlier — "coverage works for PDF
accounts, silently doesn't exist for CSV accounts" would otherwise ship as a confusing asymmetry to a
possibly-large population from day one. This is a five-minute check, not a research project — recommend
Sid or an admin run it directly rather than blocking further proposal work on it.

*(Resolves the open question originally left in §11's Phase 4 placement.)*

### 0.5 Two further refinements, not required but folded in

**Coverage was already meant to be persistent and queryable, not a one-time event.** §9's
`GET /api/v1/accounts/{accountId}/coverage` was always queryable on demand, not only surfaced once at
import time — the Phase 1/Phase 2 split in §11 was about *where in the UI* it first appears (an
import-time banner before a persistent Coverage Timeline view), never about the underlying data being
transient. Worth stating explicitly, since the phasing language could read as "coverage only exists as a
one-time warning," which was never the intent.

**`InsightsDto` should carry coverage metadata internally, even before any UI surfaces it.** A good, cheap
addition, folded into §8's scope now rather than added later as a breaking change. `InsightsDto` is a flat
`(sentences, movers)` record today with no metadata envelope, so this is a genuinely new field, not a slot
that already exists — but adding it while §8's work is being built anyway costs little, and avoids a
second feature quietly reintroducing the exact "Insights doesn't know what it doesn't know" problem this
whole proposal exists to fix. Recommend `InsightsDto` gain a `coverageCaveat: CoverageCaveat | null`
field (populated whenever the reporting window intersects a gap, null otherwise) alongside the Phase 3
work in §11 — internal/API-only until a separate UI decision is made about surfacing it.

### 0.6 Balance authority for superseded statements — and a pre-existing hazard this closes, not just a new rule

Checked what happens today, before proposing a new rule: `ImportService.isMostRecentStatementForAccount`
(the gate that decides whether a statement's closing balance may overwrite `Account.balance`) compares
only `statementPeriodEnd` values — `importedAt`/import order is never read anywhere in that path. Its tie
case matters here: `!latestOther.isAfter(thisStatementEnd)` treats an *equal* period-end as "most recent,"
so two statements covering the identical period both satisfy the gate. Concretely, for the corrected
re-upload example — July v1 closing 100,000, later July v2 closing 102,000, same period — today's logic
(with no supersession concept at all yet) sets `Account.balance` to 102,000 the moment v2 is confirmed,
simply because it ties on period-end and is being confirmed now. But this isn't a durable decision: if
v1 were somehow re-confirmed again later, the identical tie logic would flip `Account.balance` straight
back to 100,000 — there's no persisted precedence, only "whichever tied statement was confirmed most
recently, recomputed fresh every time." **This hazard exists today, independent of whether supersession
ships** — it's simply invisible right now because nothing creates same-period duplicates on purpose yet.

**Rule this proposal adds:** only the active (non-superseded) statement for a given period participates
in `Account.balance`, coverage, and Insights. Concretely:

- When a new statement's period exactly matches an existing active statement's period (§0.3), and the
  user confirms replacement, the prior statement's `supersededBy` is set to the new one's id. The new
  statement is now the account's active statement for that period.
- `isMostRecentStatementForAccount`'s existing period-comparison logic is unchanged for comparing
  *different* periods — supersession only decides which statement is authoritative *within* one period,
  it doesn't change which period drives `Account.balance` across the account's whole history.
- A statement can be superseded more than once (v1 → v2 → v3). Active is simply "nothing points at me":
  `WHERE supersededBy IS NULL`, for whichever statement is the current leaf of the chain — no need to
  re-target earlier links when a new supersession happens.
- This closes the tie-break hazard above by construction: with supersession, a superseded statement is
  never confirmed again through the normal flow, so there's no path back to the stale balance.

*(Resolves review concern A.)*

### 0.7 Gap acknowledgment lifecycle

The scenario worth designing for precisely: a gap is acknowledged (June missing), then June is imported
(gap closes), then June's statement is later removed or itself superseded away entirely (gap reappears
with the *same* `gapStart`/`gapEnd` as before). Should the original acknowledgment silently suppress the
reappeared gap?

**No — recommend event-driven invalidation, not date-based persistence.** An acknowledgment is deleted
(not merely checked-and-ignored) whenever any `StatementImport` create or supersession event for that
account has a period intersecting the acknowledged gap's `[gapStart, gapEnd]`. Walking through the
scenario: June's import intersects `[Jun 1, Jun 30]` → the old acknowledgment is deleted at that point
(harmless — the gap is gone anyway, nothing left to suppress). If June's statement is later removed, that
event *also* intersects `[Jun 1, Jun 30]` → nothing to delete (already gone), and when the gap
recomputes as present again, there's no surviving acknowledgment, so the user is correctly re-prompted.

This is scoped narrowly on purpose: only intersecting events invalidate an acknowledgment, so importing
an unrelated statement for a *different* period on the same account never clears an unrelated
acknowledgment — avoiding the exact nagging-fatigue problem §5's acknowledgment table exists to prevent,
while still not letting a stale acknowledgment silently reappear over genuinely changed underlying data.

*(Resolves review concern B.)*

### 0.8 Gap severity — a correction to this document's own earlier suggestion, not just a formalization

§7 originally suggested using `OpeningBalanceCarryForward`'s computed disagreement as a severity signal.
On closer inspection, prompted by the request to formalize this, **that suggestion is narrower than it
looked and doesn't actually cover the review's own example.** `OpeningBalanceCarryForward` is only
consulted when the *new* statement fails its own internal self-check (opening + net ≠ its own claimed
closing) — and a genuine multi-month gap (May imported, July imported, June missing) essentially never
triggers that: July is an honest, self-consistent bank document regardless of what happened in June, so
its own arithmetic checks out and carry-forward is never invoked. Concretely: the review's own example —
May closes 500,000, June opens 12,000 — is precisely the case where this signal would **not** fire, since
July's own statement, taken alone, reconciles with itself. The original suggestion would have left
severity at `UNKNOWN` for the most common real gap shape, which isn't useful.

**Better signal, and one that doesn't depend on `OpeningBalanceCarryForward` at all:** whenever a gap is
detected in the first place, both bounding values already exist by construction — the prior statement's
closing balance and the next statement's opening balance. Their plain difference is available for every
detected gap, not just the rare self-reconciliation-failure case:

```
GapSeverity: LOW | MEDIUM | HIGH | UNKNOWN

delta = abs(priorStatement.closingBalance − nextStatement.openingBalance)

UNKNOWN  -- either boundary value is missing/unextracted (should be rare once a gap is detected,
            since detection itself requires both bounding statements)
LOW      -- delta < ₹1,000        (illustrative threshold, not calibrated -- revise freely)
MEDIUM   -- ₹1,000 ≤ delta < ₹25,000
HIGH     -- delta ≥ ₹25,000
```

`UNKNOWN` becomes the rare case rather than the common one, which is what severity is actually for.

*(Corrects §7's original suggestion and resolves review concern C.)*

### 0.9 Coverage API summary fields

Agreed — every consumer would otherwise derive these independently. §9's response gains:

```json
{
  "accountId": "...",
  "coverageStatus": "HAS_GAPS",
  "coveragePercentage": 91.7,
  "hasGaps": true,
  "hasOverlaps": false,
  "segments": [ ... ],
  "gaps": [ { "gapStart": "2026-06-01", "gapEnd": "2026-06-30", "daysMissing": 30,
              "severity": "MEDIUM", "acknowledged": false } ],
  "overlaps": [ ... ]
}
```

`coverageStatus` ∈ `{ COMPLETE, HAS_GAPS, HAS_OVERLAPS, HAS_GAPS_AND_OVERLAPS }`. `coveragePercentage` is
days-covered ÷ days-in-account-history — a plain, literal ratio, not a fabricated composite (consistent
with §4's stance against blended scores; a percentage of *actual days accounted for* is a fact, not a
weighted judgment call).

*(Resolves review concern D.)*

### 0.10 Account identity drift — a genuinely new risk, not previously documented

Checked directly rather than assumed: existing account-matching (`accountMatch.ts`'s trailing-digit
suffix comparison; server-side `ImportService.resolveTargetAccount` via `ProductIdentity`) already
documents and mitigates masking-format *variance within one match attempt* — e.g. "XXXXXX4587" vs.
"XX4587" resolving to the same account. But **no existing code or doc addresses the same real-world
account already having been split across two different `accountId` rows, persisting, and needing
reconciliation after the fact.** This is genuinely new territory this review is the first to name — worth
being direct about that rather than implying it was already covered.

**Recommend explicit non-goal for the coverage engine itself, but a tracked risk, not a dismissed one.**
`StatementCoverageAnalyzer` should not attempt to detect or merge split accounts — that's account
identity resolution's job (a different subsystem, and one the roadmap doc's own canonical-transaction
design already treats as a separate concern from the relationship graph, for the same reason: collapsing
two different problems into one mechanism forces every consumer to disambiguate on every read). What
coverage *should* do: if identity resolution ever changes in a way that splits or merges historical
accounts, a resulting "phantom gap" (May on one `accountId`, July on another, both really the same
account) should be diagnosable, not invisible — coverage output already carries `accountId` and the
underlying `StatementImport` ids per segment (§9), which is enough for the admin Import Explorer trace
tooling (roadmap doc, Part 9) to investigate if a user reports a gap that shouldn't exist. No new
mechanism needed to make this diagnosable; just don't build something that would hide it either.

*(Resolves the review's account-identity loophole — named as a tracked risk, not a non-goal for the
whole problem, only for this engine's scope.)*

---

## 1. Current behavior, traced precisely

Scenario: a user imports April and May statements for an account (consecutive), then imports July
directly — June is never uploaded.

Traced against the actual import pipeline (`backend/src/main/java/com/finora/imports/ImportService.java`,
`persistSection`):

1. July's own **opening balance** reconciles against July's own printed closing balance and its own
   transactions (`ClosingBalanceGuard.assess(...)`) — it's an internally consistent bank document. This
   check passes, so `OpeningBalanceCarryForward` (added in PR #581, this session) is **never consulted**.
   May's closing balance is not compared against or substituted into July at all.
2. July's own **closing balance** becomes `Account.balance` directly (`ImportService.java:1183-1193`) —
   not derived by summing transactions. The dashboard reads `Account.balance` directly
   (`DashboardService.java:348,354`), so the balance shown is correct.
3. **Nothing detects the missing June statement.** `StatementImportRepository.findPriorStatementClosingBalanceForAccount`
   just grabs the most recent prior statement by date — no adjacency or continuity check exists anywhere
   in the pipeline. `ImportService.summarise()` only appends a warning when
   `openingBalanceDecision().carriedForward()` is true, which never fires in this scenario, so **no
   warning is shown at all.**

Net effect: **the balance is right, but June's transactions are silently absent from the ledger**, and
nothing tells the user. Anything that reads transaction history — Insights' month-over-month movers,
category totals, any June-specific reporting — sees June as a month with ₹0 activity, indistinguishable
from a month that was genuinely imported and genuinely quiet. This is not a reconciliation bug (nothing
computed anything *wrong*); it's a data-completeness problem the pipeline has no concept of.

---

## 2. Gap analysis

| Question | Today | Gap |
|---|---|---|
| Do we know if an account's statement history has a hole in it? | No | Core gap — this proposal |
| Do we know if two imported statements' periods overlap? | No (only transaction-level duplicate detection exists, `ReconciliationService`) | Statement-level overlap is a distinct, cheaper, earlier signal than N individual duplicate-transaction warnings |
| Does Insights know when it's computing over an incomplete window? | No — `InsightsService.pipeline()` derives `currentMonth`/`priorMonths` purely from whichever months have transaction rows; there is no notion that a month might be silently missing | Real risk of a misleading "spending up 100%" against a phantom ₹0 baseline |
| Is there an existing precedent for *not* inventing a blended confidence score? | Yes — twice | See §4 |

---

## 3. What "the actual statement period" already means in this codebase — and why the domain model must use it, not transaction dates

This is the one hard constraint given for this design, so it's worth grounding precisely rather than
asserting it: `StatementImport.statementPeriodStart`/`statementPeriodEnd` are populated exclusively from
`request.statementPeriodStart()/End()` in `persistSection`, which for PDFs originates in
`PdfMetadataExtractor.extract()` — regex matching against a printed "Statement/Billing Period: ..."
label in the document's own header text, with a fallback to `TransactionTableDateRangeExtractor` for
banks (e.g. Kotak) that print the range in the transaction table's own repeated column header instead of
a header block. Both sources are **printed text**, never derived data.

Critically, a transaction-min/max fallback **used to exist and was deliberately removed as a bug fix** —
`PdfPreviewGenerator.buildDetectedAccountInfo`'s own comment states it was "confirmed wrong against a
real Kotak Mahindra Bank credit-card statement" and now "stays null rather than guessing one from the
rows." That's not a hypothetical risk this proposal is inventing caution against — it's a bug that
actually shipped and actually got reverted in this exact codebase. Any coverage model built on inferred
transaction ranges would be resurrecting a bug that was already found and fixed once.

**One real, current gap this creates for coverage specifically: CSV imports never set these fields at
all.** `statementPeriodStart`/`End` stay null for every CSV-imported statement — confirmed absent from
`CsvParser.java`/`StatementValidator.java`. This means **coverage tracking as designed here only works
for PDF imports out of the box.** See §7 for the recommended treatment (an explicit, separately-labeled
estimate for CSV — never silently reusing the PDF mechanism's guarantee).

---

## 4. Trust framework — pushing back on the single-score design

The request's own example (`Account Health: 96/100`) is a natural thing to want, and worth addressing
directly rather than building around quietly: **this codebase has already tried and rejected exactly
that shape, twice, independently.**

- `ImportVerifier`'s own class comment states the principle in general form: "a weighting policy invented
  before there is anything to calibrate it against is a guess with an authoritative appearance." It
  deliberately has no overall-status field for this reason.
- The roadmap doc's Part 5 (Source confidence engine) records an actual founder-review correction: *"the
  first draft blended source trust and match quality into one number. That's wrong — a perfect PDF match
  (low-trust source, high-quality match) and a sloppy AA match (high-trust source, weak match) shouldn't
  be able to land on the same score and look identical."* It ships two separate fields —
  `source_trust`, `match_confidence` — and states plainly that nothing downstream may collapse them back
  into one.

A blended "Account Health: 96/100" is the same mistake in a new place: it would compress "1 month
missing," "2 statements overlap," "this account's balance doesn't reconcile," and "this transaction came
from a low-trust source" into one number that means nothing specific and can't be acted on. A user (or
Sid, looking at the founder dashboard) sees 96 and has no idea which of four unrelated problems, if any,
caused the missing 4 points.

**Recommendation: no blended score, in this proposal's scope.** Surface the same handful of signals this
document already needs *as themselves*, next to each other, not fused:

- Coverage completeness: *"11 of 12 months covered — 1 gap (June 2026)"*
- Balance corroboration: pass/fail per statement (already computed by `ClosingBalanceGuard` — nothing new
  to build, just something new to display)
- Overlap/duplicate-period flags: count, if any
- Source trust: `SourceTrust`'s existing value, shown, not blended in

If a single visible number is wanted later for marketing or UX simplicity, that's a legitimate call — but
per the roadmap doc's own resequencing logic (their confidence-engine formula was deliberately deferred to
"once real usage data" exists to calibrate it), it should be a deliberate later decision made with real
data, not something this proposal's first phase bakes in by default.

---

## 5. Domain model

**Recommendation: derive coverage at read time; do not persist a coverage table as the primary
mechanism.** Reasoning, grounded in two existing precedents rather than a preference:

1. `InsightsService` already computes everything — totals, movers, top merchant — on the fly from
   transaction rows with zero persistence, precisely so there's nothing to keep in sync; the Insight
   Explorer's whole purpose is re-running that same computation in a debug mode. Coverage is the same
   shape of problem: a report over existing rows, not new state.
2. The roadmap doc's own Phase 1 already chose the smaller-footprint option once in an almost identical
   spot — a full `credit_card_statement` table was designed, then shipped instead as fields on the
   existing `statement_imports` table because that was sufficient. A `StatementImport` row already has
   everything a coverage computation needs (`accountId`, `statementPeriodStart`, `statementPeriodEnd`);
   there is no new fact to store.

A per-account statement list is small (tens of rows, not thousands), so recomputing at read time costs
nothing meaningful and needs no cache-invalidation logic, migration, or backfill for the core mechanism.

```
CoverageSegment   { statementImportId, periodStart, periodEnd }        -- one per StatementImport
CoverageGap       { accountId, gapStart, gapEnd, daysMissing }         -- derived: the space between
                                                                            two adjacent segments
CoverageOverlap   { accountId, segmentAId, segmentBId, overlapStart,
                     overlapEnd }                                      -- derived: two segments whose
                                                                            ranges intersect
CoverageReport    { segments, gaps, overlaps }                         -- the return shape
```

Following the shape of this codebase's own small, pure domain classes (`OpeningBalanceCarryForward`,
`ClosingBalanceGuard`, `BalanceSequenceResolver`, `AccountBalanceConvention` — each a focused class with
one clear question to answer): a new `StatementCoverageAnalyzer.analyze(List<CoverageSegment> sortedByPeriodStart) -> CoverageReport`,
a pure function with no repository or side effect of its own. Call sites feed it whatever
`StatementImport` rows they already have.

**One narrow exception that does need persistence:** a user dismissing a gap ("I know June is missing, I
never got that statement, stop reminding me"). This mirrors an existing, proven precedent —
`notDuplicateConfirmedAt` already makes a user's "not a duplicate" call stick permanently, and the
roadmap doc's graph design gives `USER_CONFIRMED` edges the same permanence. A small new table —
`statement_coverage_acknowledgment(userId, accountId, gapStart, gapEnd, acknowledgedAt, reason)` — is the
only new persisted state this proposal needs. (I did not find an existing generic
dismissal/acknowledgment mechanism to reuse instead — worth a quick check before implementation in case
one already exists under a different name.)

### Call sites (one function, three consumers — not three implementations)

- **Import-time:** in `ImportService.persistSection`, alongside the existing opening/closing-balance
  checks, to produce the immediate warning (§8).
- **Read-time (Insights):** `InsightsService.pipeline()` (or a lightweight sibling call) runs the same
  analyzer over the account(s) in scope, to annotate output with which months, if any, fall inside a
  known gap (§6).
- **Admin (founder dashboard):** the same function backs a per-account coverage endpoint extending the
  roadmap doc's planned "Import Explorer" (§9 there) rather than a new, separate admin surface.

---

## 6. Overlap detection

> **Superseded by §0.1:** the raw interval test below flags a false positive on every PNB statement pair
> (and possibly other banks with the same boundary-reprint convention). Use §0.1's `isAdjacent`/`isGap`/
> `isOverlap` definitions instead of the rule as originally written here.

Given each segment is already `[periodStart, periodEnd]`, overlap is plain interval math:
`A.start ≤ B.end && B.start ≤ A.end`, scoped strictly per `accountId` — coverage must never compare
periods across different accounts, including different accounts of the same user, or a legitimate
non-calendar-aligned credit-card cycle on one account could be misread as conflicting with a calendar-
month statement on another.

Two cases, treated differently:

- **Exact duplicate period** (same account, same start, same end) — the common "re-uploaded the same PDF
  twice" case. Today this is only caught late, one transaction at a time, by `ReconciliationService`
  after a second full `StatementImport` row and its transactions already exist. A statement-level
  pre-check, run before persisting, is a strictly earlier and cheaper signal: *"this exact period was
  already imported for this account on [date] — import anyway?"* — surfaced once, not as N duplicate-
  transaction warnings after the fact.
- **Partial overlap** (e.g. Jun 15–Jul 15 overlapping an existing Jun 1–Jun 30) — could mean a corrected
  re-upload, a wrong-account upload, or (see §7) a broader statement type that legitimately encloses
  narrower ones. Not auto-resolved; surfaced for the user to decide, same as the exact-duplicate case.

---

## 7. Edge cases

> **Corrected by §0.8:** the severity signal proposed just below turned out not to cover this bullet's
> own example. Use §0.8's boundary-difference definition instead.

- **Missing month (May → July):** the core case; handled by gap detection above.
- **Large balance discontinuity (May close ₹500,000, June open ₹12,000):** as traced in §1, if July's own
  opening balance reconciles against July's own totals, this is simply correct data describing real
  activity Finora never saw — not something to "catch." Where a *new* signal genuinely exists: if July's
  own opening balance does **not** reconcile, `OpeningBalanceCarryForward.Decision` already computes the
  size of the disagreement against the prior close. That delta is a real, already-computed signal worth
  feeding into gap severity — "a statement is missing here, and it likely had significant activity" is a
  more useful message than a generic one, and costs nothing new to compute.
- **Out-of-order imports (July first, then June later):** handled with zero special-casing, because
  coverage is derived fresh from all `StatementImport` rows sorted by period, not by import order — this
  is a direct consequence of the "derive, don't persist a sequential log" choice in §5, not something
  that needed separate design.
- **Mixed statement types (monthly + annual):** genuine open question — **I could not verify whether
  Finora supports importing an annual/consolidated statement today**, and did not want to guess. If it
  exists, a broader statement's period legitimately *encloses* several already-imported narrower ones;
  that should be modeled as satisfied coverage, not a conflicting overlap. Flagging as unresolved rather
  than designing blind. **See §0.2** — confirmed by direct code search (not just "not found by me") that
  this concept doesn't exist anywhere in the codebase today, plus a safe-degradation recommendation that
  doesn't require Sid to resolve the product question before Phase 1 can ship.
- **Credit card cycles (15 Jun–14 Jul, 15 Jul–14 Aug):** no special-casing needed. Per the roadmap doc's
  own Phase 1 finding, `statementPeriodStart`/`End` are populated identically regardless of `Account.Type`
  — a CC statement's period is whatever was printed, same field, same extractor. Gap/overlap detection is
  pure interval math per account regardless of account type.
- **Same statement imported via PDF and CSV:** today this creates two independent `StatementImport` rows,
  and — per §3 — the CSV one has no period data at all, so it's invisible to coverage entirely, not just
  mismatched. This is a real gap in the gap-detector, not solved by this proposal's core mechanism.
  Recommended treatment, kept deliberately separate from the PDF path: derive an *estimated* period for
  CSV only, either from explicit user-entered date range at upload or (only here, only for CSV) from the
  transaction min/max — but carrying an explicit `estimated: true` flag that PDF-derived segments never
  have, so an estimate is never silently treated as the same guarantee as a printed value. This is the one
  place a transaction-derived range may belong, and only because CSV has no printed period to read at all.
- **Partial statement uploads / missing pages / truncated exports:** a parsing/extraction-completeness
  problem (already an active investigation area — `c8-extraction-sufficiency-investigation.md`), not a
  cross-statement coverage problem. Out of scope here; flagged as a boundary, not re-solved.

---

## 8. Insight safety

`InsightsService.pipeline()` derives `currentMonth`/`priorMonths` purely from which months have
transaction rows — no existing notion of a month being untrustworthy. Recommendation:

- **Movers (month-over-month comparisons):** when any month inside the `priorMonths` baseline window
  intersects a known, unacknowledged `CoverageGap`, exclude that month from the baseline rather than
  computing against it. Silently including a gap month (₹0 activity, because it was never imported) as a
  real data point risks exactly the misleading "spending up 100%" the request called out — comparing
  against a phantom low baseline. Excluding is simpler and strictly safer than trying to annotate every
  downstream sentence with a caveat.
- **Current reporting month itself:** if the *current* month intersects a gap, add one new top-level
  sentence — *"Some transactions for June 2026 may be missing — import that statement to complete your
  history."* — reusing the exact pattern PR #589 just shipped for the new-category sentence: gate on data
  presence, degrade gracefully, one clear sentence rather than reworking every existing one.
- Confidence calculation stays where the data already lives — `StatementCoverageAnalyzer`'s output is
  passed into `InsightsService`, not recomputed inside it; Insights consumes coverage, it doesn't own it.

---

## 9. API design

> **Extended by §0.9:** the response below is missing summary fields (`coverageStatus`,
> `coveragePercentage`, `hasGaps`, `hasOverlaps`) and per-gap `severity` (§0.8) — see §0.9 for the fuller
> shape.

Mirrors the shape the roadmap doc already established for `GET /api/v1/transactions/{id}/explanation`:

```
GET /api/v1/accounts/{accountId}/coverage

{
  "accountId": "...",
  "segments": [
    { "statementImportId": "...", "periodStart": "2026-04-01", "periodEnd": "2026-04-30" },
    { "statementImportId": "...", "periodStart": "2026-05-01", "periodEnd": "2026-05-31" },
    { "statementImportId": "...", "periodStart": "2026-07-01", "periodEnd": "2026-07-31", "estimated": false }
  ],
  "gaps": [
    { "gapStart": "2026-06-01", "gapEnd": "2026-06-30", "daysMissing": 30, "acknowledged": false }
  ],
  "overlaps": []
}
```

Admin: a sibling `GET /api/v1/admin/accounts/{accountId}/coverage`, same shape, gated by the same
`PLATFORM_DIAGNOSTICS_VIEW` permission already used by `AdminImportTraceController` — extending the
roadmap doc's planned "Import Explorer," not a new admin subsystem.

---

## 10. UI/UX surface area

- **MVP — import-time warning:** reuse the *exact* existing visual vocabulary in `Import.tsx` — the
  amber `bg-warning-bg`/`border-warning` box with an `AlertTriangle` icon, appended into the same
  `warnings: string[]` list `openingBalanceDecision().reason()` already populates. Zero new UI components.
- **Phase 2 — Coverage Timeline:** `StatementHistory.tsx` already groups statements by account in
  collapsible cards. A small inline strip above each account's list (`Jan ✅ Feb ✅ Mar ⚠ Apr ✅`) fits
  directly into that existing structure rather than a new screen.
- **Nice-to-have — dismiss/acknowledge:** an action on the gap banner writing to
  `statement_coverage_acknowledgment` (§5).
- **Explicitly not recommended, at least for this scope:** a numeric "Account Health" score — see §4. If
  a single-line summary is wanted, prefer an honest, literal one ("11 of 12 months covered") over a
  fabricated composite.

---

## 11. Implementation roadmap

> **Updated per §0.3 and §0.4:** statement supersession moved into Phase 1 (it shares its underlying
> check with exact-duplicate-period detection, and was a genuine scope gap, not a deprioritized item).
> CSV coverage's Phase 4 placement should be confirmed or revised by the one query in §0.4 before this
> phasing is treated as final.

| Phase | Scope | Depends on |
|---|---|---|
| **1 — Detection core** | `StatementCoverageAnalyzer` (pure function), import-time warning (reuses existing warning UI), `GET /api/v1/accounts/{accountId}/coverage`, exact-duplicate-period + supersession handling (§0.3). No new tables except the `supersededBy` FK. | Nothing — works entirely off fields that already exist |
| **2 — Surfacing** | Coverage Timeline on `StatementHistory.tsx`; admin coverage endpoint extending Import Explorer; `statement_coverage_acknowledgment` table + dismiss action | Phase 1 |
| **3 — Insight safety** | Movers exclude gap months from baseline; current-month gap sentence; `InsightsDto.coverageCaveat` (§0.5) | Phase 1 (does not need Phase 2) |
| **4 — CSV coverage (pending §0.4)** | Explicit, separately-labeled estimated period for CSV imports (§7) — placement here is provisional until the `source_format` query in §0.4 is actually run | Phase 1 |
| **Not in scope** | Any blended "Account Health" score (§4) — revisit only with real usage/calibration data, matching the roadmap doc's own deferral of its confidence-formula work for the same reason | — |

Phase 1 and Phase 3 are independent of each other and of Phase 2 — either can ship first.

---

## 12. Risks

- **False positives on legitimately dormant accounts.** A secondary account with genuine multi-month
  non-activity is not the same as a missing statement. Mitigation: a gap requires two real *bounding*
  statements on both sides — never speculate that the most recent period is "incomplete" just because no
  newer statement has been imported yet.
- **CSV's missing period data (§3, §7)** is a real, current blind spot this proposal's core mechanism does
  not close on its own — Phase 4 is optional precisely because it's a separate, lower-confidence
  extension of the same idea, not a prerequisite for Phases 1–3.
- **Gap-notification fatigue.** Addressed by the acknowledgment table (§5) — a dismissed gap must not
  resurface every time coverage is recomputed.
- **Scope creep into the trust-framework ask (§4).** Worth stating plainly: the request's §4 asks for a
  scoring model, and this document's answer is "don't build one yet, for a specific, precedented reason."
  That's a real disagreement with part of the request, not an omission — surfaced here rather than
  silently narrowed.
- **Account identity drift (§0.10).** If account-identity resolution ever splits or merges what should be
  one real account across two `accountId`s, coverage would show a phantom gap or phantom completeness.
  Explicit non-goal for this engine to detect or fix — that's a different subsystem's job — but a tracked
  risk, not a dismissed one: coverage output carries enough (`accountId`, per-segment `statementImportId`)
  to make it diagnosable via the admin Import Explorer if it ever happens, rather than invisible.
