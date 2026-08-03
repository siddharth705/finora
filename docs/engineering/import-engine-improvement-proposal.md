# Statement Import Engine — Engineering Improvement Proposal

**Status:** Proposal only. **Nothing here is implemented**, deliberately — this is Pass 2 of the
hardening phase, whose whole point is to keep "fix immediately" (bugs, done in Pass 1) separate
from "evaluate first" (improvements, this document). Prioritise before any of it is built.

**Scope of the review:** the statement import subsystem end to end — backend pipeline, product
classification, validation, identity, staging/confirm, review screen, Investments, regression
corpus, performance, security, error handling, observability, documentation.

**What this excludes:** anything already fixed in Pass 1 (`f0a9475`, `00a1933`), and anything in
`financial-document-intelligence-principles.md` that is already gated as a deliberate direction
(Phases 3–6 there). Where an item overlaps a gate, it says so.

---

## Summary

| # | Improvement | Impact | Effort | Priority |
|---|---|---|---|---|
| 1 | Re-capture the three traces with the current redactor | High | S (yours) | **High** |
| 2 | Validate the coverage metrics before anything reads them | High | S | **High** |
| 3 | Give `ProductIdentityResolver` an indexed lookup | Medium | S | **High** |
| 4 | Collapse the six-overload `confirm()` chain | Medium | M | Medium |
| 5 | Split `DetectedAccountInfo` (25 components) into nested records | Medium | M | Medium |
| 6 | A "why was this row dropped" surface for the review screen | High | M | Medium |
| 7 | Make classification re-runnable from stored evidence | High | M | Medium |
| 8 | Property-based tests for the amount/date parsers | Medium | M | Medium |
| 9 | Extract the review screen out of `Import.tsx` (1168 lines) | Medium | M | Medium |
| 10 | Per-signal confidence surfaced in the UI, not just the API | Medium | S | Low |
| 11 | A capability-coverage gate in CI | Medium | S | Low |
| 12 | CSV parity for deposit attributes | Low | M | Low |

---

## High priority

### 1. Re-capture the three traces with the current redactor

**What.** `PdfTraceRedactor`'s allowlist had no deposit vocabulary when the three committed traces
were captured, so `Maturity Date` was masked to `Xxxxxxxx Date` and `Deposit(Mnth)` to
`Deposit(Xxxx)`. The allowlist is fixed; the traces are not, and a committed trace cannot be
un-redacted.

**Why it is valuable.** These are the only real-document fixtures in the repo. Today the composite
trace can prove the deposit sections are *not accounts* but cannot prove they are *deposits* —
the evidence was redacted out of it. Every future classification change is being regression-tested
against a corpus that is missing the vocabulary it classifies on. A synthetic fixture stands in,
but a synthetic fixture only reproduces what someone already understood.

**Impact.** Restores the corpus's ability to test the capability it exists for; lets
`CompositeMultiProductClassificationTest`'s trace half assert `FIXED_DEPOSIT`/`RECURRING_DEPOSIT`
and lets the synthetic stand-in be deleted.

**Effort.** Small, but **not mine** — needs a machine holding the original PDFs.

**Risks / trade-offs.** The re-captured traces must be re-checked for PII before commit; the
hygiene hook now covers all source files, so this is guarded rather than manual.

**Dependencies.** None.

---

### 2. Validate the coverage metrics before anything reads them

**What.** `CapabilityCoverageService` produces counts today. Nothing has checked those counts
against known cases — e.g. import a document known to activate exactly five capabilities and
assert the map says five.

**Why it is valuable.** This is the step the principles doc's own sequencing names and that is
easiest to skip: *collect → store → **validate** → dashboard → decide*. Every later use of these
numbers (a CI gate, a review threshold, prioritising the backlog) inherits their correctness. A
dashboard on unvalidated metrics looks authoritative and is not.

**Impact.** High and preventive — it is what makes items 11 and the whole Phase 5 direction safe to
build on later.

**Effort.** Small. An integration test that runs the known fixtures through and asserts the
aggregate, plus a note in the doc recording that validation happened.

**Risks / trade-offs.** None meaningful. The risk is in *not* doing it.

**Dependencies.** Ideally after #1, so validation runs against a corpus that is not missing
vocabulary.

---

### 3. Give `ProductIdentityResolver` an indexed lookup

**What.** `resolve()` calls `accountRepository.findByUserId(userId)` and loops every account in
memory for each product being confirmed. V49 already added
`idx_accounts_user_product_identity`; nothing queries through it.

**Why it is valuable.** Confirming a composite statement resolves identity once per product, so a
statement with four products does four full account scans. It is fine at personal-finance volumes
today and it is the kind of thing that is invisible until someone has 200 accounts or a bulk
import path exists.

**Impact.** Medium — a correctness-neutral performance and scalability fix.

**Effort.** Small. A `findByUserIdAndProductIdentityHash` repository method, with the in-memory
scan kept only for the masked-digit fallback (which genuinely cannot be indexed).

**Risks / trade-offs.** The fallback path still scans, so this is a partial win; splitting the two
paths makes the resolver slightly less uniform to read.

**Dependencies.** None.

---

## Medium priority

### 4. Collapse the six-overload `confirm()` chain

**What.** `ImportService` exposes `confirm` at three, five, six and nine arguments plus
`confirmSession` and `confirmMultiSection`, each delegating inward with a growing tail of nulls.
Phase 2 and Phase 4 each widened the innermost signature, and every intermediate overload had to be
edited to pass one more null.

**Why it is valuable.** The tail-of-nulls pattern is why two separate compilation breaks happened
during this work — a caller passing eight arguments to a nine-argument method is a mechanical
error the compiler catches, but only after every overload has been touched. A parameter object
(`ConfirmContext`) makes the next widening a one-line change.

**Impact.** Medium — maintainability, and fewer mechanical edits per future change.

**Effort.** Medium. Mechanical but wide; ~6 call sites plus tests.

**Risks / trade-offs.** A large diff across a heavily-tested path. Worth doing in isolation, not
alongside a behaviour change, so the diff stays reviewable.

**Dependencies.** None.

---

### 5. Split `DetectedAccountInfo` into nested records

**What.** It now carries 25 components — bank metadata, statement metadata, balances, product
classification, identity, and seven deposit attributes — and every test that constructs one passes
a long positional null tail.

**Why it is valuable.** Positional construction of a 25-component record is where a silent
field-order mistake hides; the compiler cannot catch two adjacent `BigDecimal`s being swapped.
Grouping into `BankInfo` / `StatementPeriod` / `ProductInfo` / `DepositTerms` makes each group
constructible and assertable on its own.

**Impact.** Medium — maintainability and a real class of silent bug removed.

**Effort.** Medium; touches the DTO, both generators, and every test that builds one.

**Risks / trade-offs.** Changes the JSON shape the frontend consumes, so it is a coordinated
frontend change too. That argues for doing it before the API has external consumers, not after.

**Dependencies.** None, but cheaper before #9.

---

### 6. A "why was this row dropped" surface for the review screen

**What.** Unparseable rows are surfaced with a reason, and Pass 1 added a stored histogram of
reasons and column shapes. The review screen shows the rows but does not group or explain them.

**Why it is valuable.** "Never lose information" is satisfied at the data layer and not at the
human layer: a user seeing 61 dropped rows currently sees 61 rows, not "61 rows dropped because no
date column was recognised". The grouped form is also exactly what turns a user report into a
capability-backlog entry without an engineer reading the file.

**Impact.** High for support and for the feedback loop the whole engine direction is built around.

**Effort.** Medium — a grouped UI plus a small API shape.

**Risks / trade-offs.** Care needed not to show raw statement content more prominently than
necessary; the histogram (reason + column shape) is the safe unit, the rows themselves are the
customer's.

**Dependencies.** Builds on the Pass 1 histogram.

---

### 7. Make classification re-runnable from stored evidence

**What.** `SectionEvidence` is designed to be persistable and re-scorable — Stage 1 and Stage 2 are
separately callable precisely so evidence can be re-scored without re-reading the document. It is
not actually persisted anywhere.

**Why it is valuable.** It is the difference between "we changed the classifier, hope it is better"
and "re-score every import we have ever seen and diff the results". It also makes a disputed
classification explainable months later, which is the stated reason the evidence model exists.

**Impact.** High — turns classifier changes from opinion into measurement, and is the natural
extension of the golden-snapshot idea from one document to the whole corpus.

**Effort.** Medium. An evidence column or table, plus a re-score command.

**Risks / trade-offs.** Storage growth, and evidence is derived from customer documents — the
facts are structural (column names, field presence) rather than values, so this is closer to the
histogram than to the rows, but it needs the same explicit judgement before shipping.

**Dependencies.** Overlaps Phase 3's gated "Collect Knowledge" direction; worth checking that gate
before starting.

---

### 8. Property-based tests for the amount and date parsers

**What.** `CsvParser.parseNumeric`/`parseDate` are exercised by example — each real-world format
that broke has a test. There is no test asserting invariants across generated inputs.

**Why it is valuable.** These two functions have absorbed the most real-document bug fixes in the
codebase (Dr/Cr suffixes, parenthesised markers, rupee-glyph artifacts, trailing times). Their
failure mode is silent: an amount that does not parse becomes a dropped row. Properties like
"any amount formatted by any supported convention round-trips" would cover combinations no example
test enumerates.

**Impact.** Medium — targeted at the highest-churn, highest-silence code in the pipeline.

**Effort.** Medium, plus a new test dependency (jqwik or similar).

**Risks / trade-offs.** A new dependency, and property tests are slower and occasionally flaky if
generators are written loosely.

**Dependencies.** None.

---

### 9. Extract the review screen out of `Import.tsx`

**What.** `Import.tsx` is 1168 lines holding upload, single-account review, multi-account review,
summary, re-import, and now product-detection UI, with parallel flat state and per-section state
for the same fields.

**Why it is valuable.** The single- and multi-account paths duplicate the same review logic in two
shapes, which is why a change to the review form has to be made twice. Product discovery made this
worse, not better.

**Impact.** Medium — maintainability, and it removes a real "changed one path, forgot the other"
class of bug.

**Effort.** Medium.

**Risks / trade-offs.** Frontend test coverage here is thinner than the backend's, so a refactor of
this size carries more regression risk than the equivalent backend change. Worth adding review-step
tests first.

**Dependencies.** Cheaper after #5.

---

## Low priority

### 10. Per-signal confidence surfaced in the UI

**What.** The API returns per-signal confidence and full evidence; the review screen shows the
evidence lines behind a "Why?" disclosure but not the per-signal weights.

**Why it is valuable.** Modest. It helps an engineer debugging a misclassification more than it
helps a user, and the engineer can already read the API response.

**Impact.** Low.

**Effort.** Small.

**Risks / trade-offs.** Showing numeric confidence to end users invites treating it as precision it
does not have.

**Dependencies.** None.

---

### 11. A capability-coverage gate in CI

**What.** Fail the build when a registry capability has no regression test, or when coverage drops
below a recorded baseline.

**Why it is valuable.** It is the "number that can fail a build" the measurement phase was built
to enable.

**Impact.** Medium once trustworthy — but it is **gated on #2**. A gate on unvalidated metrics
enforces a number nobody has checked, which is worse than no gate.

**Effort.** Small.

**Risks / trade-offs.** Premature adoption creates pressure to game the metric.

**Dependencies.** **Hard dependency on #2.**

---

### 12. CSV parity for deposit attributes

**What.** Deposit attribute extraction and per-row product splitting are PDF-only.

**Why it is valuable.** Consistency across formats — a user importing the same holdings as CSV gets
less.

**Impact.** Low, and speculative: no real CSV export in the corpus represents a multi-deposit
schedule.

**Effort.** Medium.

**Risks / trade-offs.** This is exactly the shape "Evidence before capability" rules out — building
handling for a document nobody has seen. **Should not be started until a real CSV motivates it**,
and is listed here only so the asymmetry is recorded rather than forgotten.

**Dependencies.** A real document.

---

## Recommended order

1. **#1** (yours) and **#2** in parallel — both are cheap and everything measurable depends on them.
2. **#3** — small, self-contained, removes a scalability cliff before it is load-bearing.
3. **#6** and **#7** — the two that most advance the feedback loop the engine direction is built on.
4. **#4**, **#5**, **#9** — the maintainability cluster; do them in isolation from behaviour changes.
5. **#8**, **#10**, **#11** — after the above; #11 only once #2 has actually been done.
6. **#12** — not until a real document motivates it.
