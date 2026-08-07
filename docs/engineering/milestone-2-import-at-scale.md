# Milestone 2 — Import at Scale

**Goal.** Make Finora capable of processing large, complex and varied financial statements reliably
in production, while giving both users and administrators complete visibility into the import
lifecycle.

One business outcome, not a collection of leftover work. The test for whether something belongs
here: *does it change what Finora can reliably import, or what someone can see about an import?* If
not, it goes elsewhere — and "elsewhere" is named at the bottom of this document rather than left
implied.

---

## Where this sits

```
v1.0          Build the engine.        Finora can reliably ingest financial data.
Milestone 2   Operate the engine.      That ingestion works at production scale, and can be
                                       watched, diagnosed and improved without an engineer.
Milestone 3   Use the engine.          Trusted financial data becomes intelligent financial
                                       decisions for the user.
```

The shift this milestone asks for is one of framing, and it is worth saying out loud because it
changes which work looks important:

> **Stop thinking in terms of fixing parser bugs. Start thinking in terms of building an import
> platform.**

A parser project asks *why doesn't HSBC import?* An import platform asks *how does Finora
continuously become better at importing every layout it encounters?* The first question produces a
fix; the second produces a corpus, a registry, and a loop an operator can turn without an engineer.
Both are in this charter, and only the second is the milestone.

Milestone 3 will be harder than this one, and it is worth knowing that now. Once ingestion is
stable, the difficulty moves from correctness to judgement: a parser has ground truth printed inside
the document, and a cash-flow forecast or a spending insight does not. There is no balance line to
check an insight against. Which is exactly why the investment here pays there — the only thing that
makes an unfalsifiable feature defensible is that everything underneath it is traceable.

---

## Why this theme

Milestone 1 made the import path *correct*: learning cannot cost an import, staging writes nothing,
a duplicate is the user's decision, and that decision survives everything downstream of it. What it
did not do is make the path *hold up* — every import is still synchronous, single-account review is
the only reviewable shape, and parser coverage improves by hand.

It is also the theme the work already landing fits without being retrofitted. `eb91c02` shipped a
durable import job queue and its worker the day this milestone was being argued about, which is
either a coincidence or the strongest available evidence about what the codebase is asking for next.

---

## Success criteria

"At scale" is a direction until it is a number. These are the conditions for calling the milestone
done, and each is checkable rather than arguable.

1. **A statement of 1,000+ transactions imports without blocking the interface.** The user gets
   progress they can watch and a browser they can close; the import survives a backend restart.
2. **Import execution is asynchronous end to end** — queued, resumable, cancellable, with retry and
   recovery from a worker that died mid-job.
3. **Multi-account statements get the same review workflow as single-account ones**, duplicate
   review included. No path silently unticks a row.
4. **Every layout Finora claims to support is represented in the redacted corpus**, and the corpus
   runs in regression on every parser change. "Claims to support" is itself a list that has to
   exist.
5. **A parser change that breaks a supported layout fails the build**, not a customer's import.
6. **An administrator can trace one import from upload through parsing, verification, learning and
   completion in a single view**, without a log or an engineer.
7. **A layout encountered in production can be identified, named, approved and tracked over time**
   — so coverage is a number that moves rather than an impression.

---

## Scope

Numbered in dependency order, which is the only ordering that is not a preference.

### 1. A corpus of redacted traces — *the gate*

Parser quality is currently asserted, not measured. Every layout improvement risks a silent
regression in one nobody re-tested.

The corpus does not exist, and by the import engine's own rules a real statement is never committed.
The path is redacted extraction traces (`scripts/trace-capture.sh`,
`docs/engineering/trace-lifecycle.md`) — reviewable, scannable, safe.

**A gate, not a deliverable.** If it slips, everything downstream slips with it and the pressure
will be to ship parser changes on assertion instead — the thing this milestone exists to stop. The
gate: *every layout we claim to support has a trace, and the list of layouts we claim to support
exists in writing.*

**Where it stands.** `CapabilityCorpusCoverageTest` holds the gate. Today: 7 of 16 declared
capabilities have at least one committed trace exercising them, from 3 traces. Measured by running
the locator over each trace and reading what fired, not by the trace's own metadata claim — a corpus
that grades itself on what it says it covers is not a gate, and every committed trace is v1 with no
metadata, so a claim-based measure would have reported perfect coverage of nothing.

That number is about the **corpus, not the parser**. A capability with no trace may work perfectly;
what it lacks is anything that would notice if it stopped. The console output says so on every run,
because "7 of 16" reads as "9 are broken" to anyone quoting it out of context.

The gate also surfaced two drifts between the registry and the engine, held as named accept-lists
because each needs a decision rather than a patch: `PRINTED_SUMMARY_TOTALS` and
`RIGHT_ALIGNED_AMOUNTS` are recorded but unregistered (invisible to the coverage map);
`UNANCHORED_ROWS_ABANDONED` is a failure signal wearing a capability's clothes; `LEADING_PLUS_CREDIT`
and `LEADING_NAME_LINE` are registered but recorded nowhere, so they report as never-activated
forever — and cannot be covered by a trace until something emits them.

**Two refinements, deliberately later.** Both make the number better and neither is worth having
before the corpus is big enough for them to say anything:

- **Capability × layout.** "`RIGHT_ALIGNED_AMOUNTS`: covered" is weaker than "covered on HDFC and
  SBI, not on HSBC or PNB". A capability exercised once is not well covered, and the current gate
  cannot tell the difference. Needs enough traces to have a second axis at all.
- **Activation frequency.** A capability observed 18,000 times and one observed 4 times deserve
  different amounts of test attention, and the coverage map already counts activations per import —
  the data exists, nothing reads it for prioritisation. This is the cheaper of the two and the one
  that would tell us which traces to capture first.

---

### 2. Layout registry — the persistence model

Layouts today are **observable but not curatable**, and that distinction is the whole work item.

What exists: `layout_fingerprint` has been persisted on `statement_imports` since V39,
`DocumentContext.buildFingerprint()` computes it, and `LayoutIntelligenceService` +
`AdminLayoutIntelligenceController` give operators an overview, unknown headers, a usage timeline
and drift detection.

What does not: `LayoutIntelligenceService` holds no repository of its own — it aggregates over
`StatementImportRepository`, deriving everything from a string column. **A layout is not a row
anywhere.** It cannot be named, given a status, approved, associated with a parser, or carry a
first/last-seen of its own. An operator can watch a layout appear and cannot do anything about it.

So fingerprints are *already* accumulating with nowhere to go, and every item below this one
produces more of them. That is why this moves early — not because it is more important, but because
everything after it wants to write into it, and because "coverage" cannot be a number that moves
while it is only ever re-derived from history.

Scoped to the **persistence model only**: a table where a fingerprint is a first-class row with a
name, a status, the parser that handles it, and first/last-seen. Small, few dependencies. The
existing intelligence layer then reads from something curated rather than inferring from aggregates.

The curation screen is item 7. Splitting them is the whole point of moving this: the table is
foundational, the screen is a finishing feature, and conflating them is what would have pushed both
to the end.

### 3. WI1A — the last synchronous batch learning path

`TransactionService.bulkRecategorize` still calls `CategorizationService.learn` synchronously, in a
loop, up to 500 times inside one transaction — the import path's exact pre-WI1 shape. One lost race
against `UNIQUE(user_id, merchant_id, category_id)` rolls back all 500.

Not an enhancement: technical debt inside a subsystem already redesigned once, and leaving it means
maintaining two learning paths forever. Small and known, which is exactly why it stops being "later"
only if it goes early. Do it alongside the one-line security fixes.

**As built.** `CategorizationService.queueLearning` is the batch counterpart of `learn`: it resolves
the merchant in the caller's transaction and hands the confirmation to the queue WI1 already built,
so only the *applying* is deferred. No second queue, no second vocabulary, and no migration — the
admin queue's projection already `LEFT JOIN`s the statement import, so a bulk event with null source
ids renders rather than hiding. Null is stated rather than invented: a bulk recategorization is not
an import and had no staging session.

One event per row, not one per distinct merchant. Each row is a real confirmation and increments
`confirmation_count` once, which is what `ConfidenceEngine.topCategory` weighs; de-duplicating a
batch would quietly change what the engine learns from it. `ImportService.confirm` queues per row
for the identical reason.

The rule is now expressed as two methods rather than a comment: **every batch learning path is
asynchronous, every synchronous one is a single action a person is waiting on.** `create`, `update`,
`updateCategory` and `confirmMerchantCategory` are unchanged and stay synchronous by design.

`BulkRecategorizeLearningIT` holds it, against a real Postgres because transaction-boundary
behaviour is invisible to a mock — the lesson `BudgetServiceTest` and `BootstrapServiceTest` taught.
Falsified before it was trusted: reverting `bulkRecategorize` to `learn` fails 4 of its 6 tests. The
sharpest of them arms `confirm` to throw *before* the batch runs, which is where the old shape died,
and asserts the outcome rather than `verify(never())` — a "confirm was not called" assertion still
passes if the call is merely moved somewhere else that shares the transaction.

**Nothing else was missed.** Every site that assigns a transaction's category was checked:
`ImportService` (queued by WI1), `BudgetService` (a budget's category, no learning),
`CategorizationService.applySideEffectRules` (no learning call), and the four single interactive
paths above. `MerchantService.merge` writes learning rows in bulk but records no confirmation and
de-duplicates by category before saving, so it never races the constraint; `AdminLearningQueueService.retryAll`
only flips statuses, and the worker still applies one event per transaction. This was the last one.

### 4. Multi-account statements reach parity

The multi-section path still auto-unticks flagged duplicate rows — the silent-filter behaviour WI5
removed everywhere else. Its review state is per detected account, so this is a restructuring job
rather than a second copy of the component, which is why Milestone 1 declined to rush it.

The last correctness gap in the import experience, and the reason to finish it before adding parser
capability.

**As built.** The restructuring is `frontend/src/lib/importReview.ts`: the include flags and the
duplicate decisions are one value, and `beginReview()` is the only way to make one. A path that
unticks a row therefore also produces the unresolved decision that blocks the import until the user
answers it — so success criterion 3's *"no path silently unticks a row"* is a property of the type
rather than a convention the next screen to stage rows has to remember. Both paths delegate to it:
the single-account screen's two pieces of state collapse into one, and each detected section holds
its own. `toConfirmedRows()` is shared for the same reason — the multi-account path hand-rolled its
own confirm payload and omitted `confirmedNotDuplicate`, so a review screen alone would have left
the user's "import anyway" honoured in the ledger and reversed by reconciliation (`55f2db0`'s defect,
one path over).

No backend change and no migration: `SectionConfirm.rows()` was already `List<ConfirmedRow>`, and
`confirmMultiSection` passes it through untouched. The gap was entirely in the client.

**Not covered, deliberately.** Two adjacent findings, neither of which is a silent untick, both
recorded rather than folded in: the multi-account `newAccount` payload still drops
`detectedProduct`/`productIdentityHash` and the seven deposit attributes, so a composite statement's
fixed-deposit section is created as an empty savings account; and mobile's `initialInclusion()` still
unticks on `likelyDuplicate` with no review screen behind it, which the mobile initiative owns.

### 5. Asynchronous imports — completing what started

`ImportJob`, `ImportJobWorker` and `V66__import_jobs.sql` landed in `eb91c02`. The row already
carries `rows_total`, `rows_processed`, `attempt_count`, `next_attempt_at`, `last_error`,
`correlation_id`, `import_session_id`, `started_at` and `finished_at` — progress, retry and
correlation are in place at the data layer.

What that leaves:

- **The user-facing half.** Progress the person who uploaded can watch, and a resumable review when
  they close the tab.
- **Cancellation.** Someone who uploaded the wrong file should not have to wait for it.
- **When async applies.** Every import, or only above a threshold? A 3-row CSV routed through a
  queue is a worse experience than a synchronous one. Measure before choosing.

### 6. Unified Import Observability

**Most of this already exists, scattered.** What the three existing tables already answer:

| Question | Where it lives today |
|---|---|
| What stage is it in? | `import_jobs.status` — coarse, not stage-level |
| How long did it take? | `statement_analysis_sessions.duration_ms` — total only |
| Where did it fail? | `failure_code` + `failure_detail` |
| Which layout matched? | `layout_fingerprint` |
| Which parser handled it? | `source_format` — format, not parser identity |
| What parse quality? | `row_count`, `unanchored_reasons_json` |
| Which merchants were learned? | `merchant_learning_events`, joinable on `import_session_id` |
| How far along? | `import_jobs.rows_processed` / `rows_total` |

The genuine gaps:

- **Per-stage timing and status.** "How long has each stage taken" is unanswerable; only the total
  is recorded.
- **Verification outcomes are not persisted.** Findings reach the staging response and are then
  gone. Nobody can ask which rules ran on an import that happened last week.
- **No single view.** Three tables share correlation IDs and nothing joins them, so one support
  question means three queries and knowing all three exist.

The name matters: **completing** observability, not building it. Close two gaps and build one view
over what is already recorded. Anything scoped as a new diagnostics subsystem would be building
two-thirds of it twice.

> The [diagnostics rule](../../CLAUDE.md) applies here as everywhere: a diagnostic earns its place
> by being able to prove a proposed capability *unnecessary*. Per-stage timing qualifies — it can
> show that a stage everyone assumed was slow is not, and stop an optimisation being built. A
> counter that only ever goes up does not.

### 7. Layout curation — closing the Layout Studio loop

Layout Studio and analysis sessions already let an operator run the engine on a document without
importing anything. With the registry table (item 2) behind it, that loop closes: an operator who
finds a layout the engine mishandles can name it, approve it and watch its coverage rather than
filing a ticket.

Last on purpose. It needs both the registry to write into and the corpus to tell whether a change
improved coverage or moved the failure somewhere nobody is looking.

### 8. PDFBox and the import engine's dependencies

Bug 30, scoped to what this theme owns. PDFBox is roughly two years behind with no CVE scan, and it
parses attacker-supplied files as a core product feature reachable by an authenticated low-privilege
user.

Inside the milestone rather than alongside it, because the parser *is* the subject — the same reason
you would upgrade PostgreSQL before doing database performance work. The rest of the dependency
backlog stays in the maintenance track.

---

## Deliberately not in this milestone

- **Merchant intelligence.** WI4A, similarity scoring, alias visualisation, bulk merge. Valuable,
  and an optimisation of a subsystem that already works — it does not change what a customer can do.
  **Milestone 3**, where it has higher leverage on top of reliable imports.
- **Cross-user merchant intelligence.** Its own milestone. It changes what a merchant *is* and
  raises a privacy question the current design never has to answer.
- **More diagnostics, counters, metrics or admin graphs** beyond the two gaps named above.
- **More verification validators.** Four ship. The next move is corpus-driven.
- **Confidence scoring on duplicate detection.** The detector matches on date AND amount AND
  description being identical. There is no spectrum to score.

---

## Running alongside, not part of the theme

**Security and production readiness.** Release-blocking engineering work, prioritised and done
during this milestone, not defining it:

- Access tokens survive session revocation — a stolen token stays valid up to 15 minutes after the
  platform has concluded it was stolen. `JwtAuthFilter` already extracts `sid`; closing this means
  validating it.
- The refresh token is still written to `localStorage`, so the XSS mitigation the HttpOnly cookie
  exists for is not delivered (Bug 03, partial).
- Account scope is absent from the JWT and unread at authorization time (Bug 18, partial).
- Login reveals account existence for suspended accounts before authentication (finding #4).
- The rest of Bug 30's dependency backlog. PDFBox moved *into* the milestone as item 8 — the parser
  is the subject of the theme, so updating it supports the theme directly rather than running beside
  it. Everything else in that backlog stays here.

**Consumer product work** continues incrementally and is not tracked here.

---

## Carried over from the Milestone 1 backlog

- Cross-browser and responsive Playwright projects, configured and never run to green.
- One `test.fixme` in `e2e/tests/admin-portal/merchant-review.spec.ts`.

---

## Dependency order

Each item unlocks the next rather than competing with it.

```
Corpus of redacted traces        the gate; parser quality is not measurable without it
        v
Layout registry (persistence)    everything below writes fingerprints into it
        v
WI1A + one-line security fixes   small and known, so they only happen if they go early
        v
Multi-account duplicate review   the last correctness gap in the import experience
        v
Async import completion          progress, cancellation, the threshold decision
        v
Unified observability            per-stage timing, persisted verification, one joined view
        v
Layout curation UI               needs both the registry and the corpus to be worth building
```

PDFBox runs wherever it fits; it blocks nothing and nothing blocks it.

The registry is split deliberately. The **table** is foundational — few dependencies, and every item
below it produces fingerprints that today have nowhere to go. The **screen** is a finishing feature.
Treating them as one work item is what would have pushed both to the end, and left the milestone
accumulating layout data it could not name.
