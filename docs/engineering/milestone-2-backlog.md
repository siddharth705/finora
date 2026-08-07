# Deferred work backlog

Everything the Import Reliability milestone deliberately did not do. It was written as a
"Milestone 2 backlog", which was wrong in one important way: a backlog is a list of leftovers, and
leftovers make an incoherent release. A milestone should represent one business outcome.

**Milestone 2 is now defined as [Import at Scale](milestone-2-import-at-scale.md)** — make Finora
capable of processing large, complex and varied statements reliably, with full visibility into the
import lifecycle. That charter pulls what it needs from this list and names what it deliberately
leaves behind. This file is the pool it draws from, not the plan.

Where each item went:

| Item | Now |
|---|---|
| Multi-account duplicate review | **Milestone 2** — closes a correctness gap |
| WI1A — bulk recategorization | **Milestone 2** — carried over, do it early |
| Corpus-driven regression | **Milestone 2** — and its first dependency |
| Cross-browser Playwright projects | **Milestone 2** — carried over |
| Merchant Intelligence Workbench (WI4A) | **Milestone 3** — higher leverage on top of reliable imports |
| Cross-user merchant intelligence | Its own milestone; changes what a merchant *is* |
| Security follow-ups, Bug 30 | Release-blocking work running **alongside** Milestone 2, not defining it |

Nothing here is a defect except where it says so. The detail below is kept because the reasoning
behind each deferral is worth more than the list of names.

---

## Merchant intelligence

### WI4A — Merchant Intelligence Workbench (Admin Portal)

WI4 gave operators a Merchant Review Center that is *sufficient*: it lists temporary merchants,
shows transaction counts, and supports approve / rename / merge / delete. It is not yet
*productive*. An operator working a backlog is currently the similarity engine — they read two
names, decide the names mean the same business, and type one into the other's merge field.

What the workbench would turn into something the system proposes and the operator confirms:

- Similarity scoring between merchants, with the evidence behind the score, so a suggested merge is
  auditable rather than a number.
- Rich side-by-side comparison — aliases, sample narrations, learning history, spend totals. "Are
  these the same business?" is not answerable from two name strings.
- Alias graph visualisation, so an operator can see what a merge would absorb before committing.
- Bulk merge and apply-to-similar, the operator-side equivalent of what WI5 gave the user.
- **Search and filter.** Currently absent, and the sharpest of these. The list is ordered
  oldest-first (deliberately — a newest-first queue buries the oldest outstanding work forever), so
  finding one account's merchants means paging through everything. The e2e suite has to backdate
  seeded merchants to make them findable at all; that workaround is this gap stated as a cost.

**Evidence first.** What the workbench should *suggest* is best answered by watching real operators
work a real backlog. Build the search and filter — that need is already demonstrated. Hold the
scoring and the graph until there is a backlog big enough to justify them.

### Cross-user merchant intelligence

The canonical registry, cross-user suggestions and platform-wide merge. Deferred by explicit product
decision (design doc §1.2): merchants, aliases and learning are per user, and the Merchant Review
Center operates within one account.

This is its own milestone, not a work item. It changes what a merchant *is*, and it carries a
privacy question the current design does not have to answer: one customer's spending shaping another
customer's categorisation is a different product from the one that exists today.

---

## Import

### Duplicate review for multi-account (multi-section) PDFs

The single-account path got WI5's review screen. The multi-section path still auto-unticks flagged
rows — the exact silent-filter behaviour WI5 removed — because its review state is per detected
account and wiring it correctly means restructuring that state rather than repeating the component.

Left alone deliberately: doing it carelessly would make the path worse, not better. The
single-account path is where the overwhelming majority of imports land.

### WI1A — move bulk recategorization onto the asynchronous learning pipeline — **done**

`TransactionService.bulkRecategorize` called `CategorizationService.learn` synchronously, in a loop,
up to `TransactionDto.MAX_BULK_IDS` (500) times inside one transaction. That is the import path's
exact pre-WI1 shape: one lost race against `UNIQUE(user_id, merchant_id, category_id)` rolled back
all 500 recategorizations.

WI1 left it alone because its scope was the import path, but the objective was to remove the last
synchronous *batch* learning path, not to leave one behind. Single interactive actions
(`updateCategory`, `confirmMerchantCategory`, `create`) stay synchronous by design — see
`CategorizationService.learn`'s doc comment.

Of everything on this list, this is the one that was arguably a latent defect rather than an
enhancement. Delivered as item 3 of Milestone 2; see that charter for what was built and for the
audit confirming no other synchronous batch learning path exists.

---

## Testing and tooling

### Cross-browser and responsive Playwright projects

Configured (`npm run test:browsers`, `npm run test:responsive`) and never run to green. They
re-execute the user-portal specs against Firefox, Edge, tablet and mobile viewports.

### Regression against a real statement corpus

Phase 14 of the milestone test brief. Needs a sanitized corpus that does not exist yet — and by the
import engine's own rules, never a committed statement. The path is redacted extraction traces (see
`docs/engineering/trace-lifecycle.md`), which are reviewable and scannable.

### One `test.fixme` in the merchant review spec

`tests/admin-portal/merchant-review.spec.ts` — the row's own presentation (that it names the account
and shows the transaction count) is not asserted. Everything around it is. Reasoning is in the
comment above the test.

---

## Deliberately NOT on this list

Recorded because the temptation is real and naming it is the cheapest defence:

- More diagnostics, counters, metrics or admin graphs. The [diagnostics
  rule](../../CLAUDE.md) applies — a diagnostic earns its place by being able to prove a proposed
  capability *unnecessary*, and the ones that exist have not yet been used in anger.
- More verification validators. Four rules ship (L3/L4/L5/L7) and the next move is corpus-driven,
  not another rule written from imagination.
- Confidence scoring on duplicate detection. The detector matches on date AND amount AND description
  being identical. There is no spectrum to score, and inventing one would put a number on a
  financial screen that nothing supports.
