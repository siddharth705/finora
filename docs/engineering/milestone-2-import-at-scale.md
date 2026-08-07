# Milestone 2 — Import at Scale

**Goal.** Make Finora capable of processing large, complex and varied financial statements reliably
in production, while giving both users and administrators complete visibility into the import
lifecycle.

One business outcome, not a collection of leftover work. The test for whether something belongs
here: *does it change what Finora can reliably import, or what someone can see about an import?* If
not, it goes elsewhere — and "elsewhere" is named at the bottom of this document rather than left
implied.

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

1. A large import runs asynchronously with progress the user can watch, and survives a restart.
2. A multi-account statement gets the same review experience as a single-account one — including
   duplicate review, which it does not have today.
3. Parser quality is measured against a corpus on every change, not asserted.
4. An administrator can answer "what happened to this import" without reading a log or asking an
   engineer.
5. The production-readiness security work is done — alongside, not as this milestone's subject.

---

## Scope

### 1. Asynchronous imports — *started*

`ImportJob`, `ImportJobWorker` and `V66__import_jobs.sql` landed in `eb91c02`. The row already
carries `rows_total`, `rows_processed`, `attempt_count`, `next_attempt_at`, `last_error`,
`correlation_id`, `import_session_id`, `started_at` and `finished_at` — so progress, retry and
correlation are in place at the data layer.

What that leaves:

- **The user-facing half.** Progress the person who uploaded can see, and a resumable review when
  they close the tab.
- **Cancellation.** A user who uploaded the wrong file should not have to wait for it.
- **The decision about when async applies.** Every import, or only above a threshold? A 3-row CSV
  routed through a queue is a worse experience than a synchronous one. Measure before choosing.

### 2. Multi-account statements reach parity

The multi-section path still auto-unticks flagged duplicate rows — the silent-filter behaviour WI5
removed everywhere else. Its review state is per detected account, so this is a restructuring job
rather than a second copy of the component, which is exactly why Milestone 1 declined to rush it.

This is the one item here that closes a known correctness gap rather than adding capability.

### 3. Import observability

**Most of this already exists, scattered.** Scoping it as new work would mean building it twice, so
here is what the three existing tables already answer:

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

- **Per-stage timing and status.** "How long has each stage taken" is unanswerable today; only the
  total is recorded.
- **Verification outcomes are not persisted.** The findings reach the staging response and are then
  gone. An operator cannot ask which rules ran on an import that happened last week.
- **No single view.** Three tables share correlation IDs and nothing joins them, so answering one
  support question means three queries and knowing all three exist.

That is the work: close two gaps and build one view over what is already recorded. Not a new
diagnostics subsystem.

> The [diagnostics rule](../../CLAUDE.md) applies here as everywhere: a diagnostic earns its place
> by being able to prove a proposed capability *unnecessary*. Per-stage timing qualifies — it can
> show that a stage everyone assumed was slow is not, and stop an optimisation being built. A
> counter that only ever goes up does not.

### 4. Corpus-driven parser regression

Parser quality is currently asserted, not measured. Every layout improvement risks a silent
regression in one nobody re-tested.

**This has a dependency that will decide whether the milestone succeeds:** the corpus does not
exist, and by the import engine's own rules a real statement is never committed. The path is
redacted extraction traces (`scripts/trace-capture.sh`, `docs/engineering/trace-lifecycle.md`) —
reviewable, scannable, and safe.

Build the corpus **first**. If it slips, everything that depends on it slips with it, and the
temptation will be to ship parser changes on assertion instead. Treat "we have 20 redacted traces
covering the layouts we claim to support" as an early milestone gate, not a late deliverable.

### 5. Layout registry evolution and Layout Studio

Layout Studio and analysis sessions already let an operator run the engine on a document without
importing anything. This milestone makes that loop closed rather than diagnostic: an operator who
finds a layout the engine mishandles should be able to do something about it without an engineer.

Scope this **after** the corpus exists. Otherwise there is no way to tell whether a registry change
improved coverage or moved the failure somewhere nobody is looking.

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
- **Bug 30 — dependencies roughly two years behind, no CVE scan.** PDFBox first: it parses
  attacker-supplied files as a core product feature, reachable by an authenticated low-privilege
  user. This one arguably belongs *inside* the theme, since the parser is the subject.

**Consumer product work** continues incrementally and is not tracked here.

---

## Carried over from the Milestone 1 backlog

- **WI1A** — `bulkRecategorize` still learns synchronously in a loop of up to 500 inside one
  transaction. The last synchronous batch learning path, and the only backlog item that is arguably
  a latent defect rather than an enhancement. Small; do it early.
- Cross-browser and responsive Playwright projects, configured and never run to green.
- One `test.fixme` in `e2e/tests/admin-portal/merchant-review.spec.ts`.

---

## Suggested order

Not a schedule — a dependency order, which is the only ordering that is not a preference.

1. **Corpus of redacted traces.** Everything about parser quality waits on it.
2. **WI1A**, and the security items that are one-line fixes. Small, known, and they stop being
   "later" the moment something else fills the calendar.
3. **Multi-account duplicate review.** Closes a correctness gap rather than adding capability.
4. **Async import completion** — user-facing progress, cancellation, the threshold decision.
5. **Observability** — per-stage timing, persisted verification outcomes, one joined view.
6. **Layout registry evolution**, once the corpus can tell whether a change helped.
