# Performance engineering methodology

How Finora finds, fixes, verifies and defends performance work.

This is the reference for optimization work. It is written from what actually happened during the
import pipeline optimizations, including the mistakes — those are the parts most worth keeping.

**The workflow, in one line:**

```
measure → optimize → verify → guard → document
```

No optimization merges without measured before/after evidence and, where practical, an automated
guard. An improvement nobody can re-measure is a claim, not a result.

---

## 1. The lifecycle

| Stage | Question it answers | Output |
|---|---|---|
| **Profile** | Where does the time or work actually go? | A measurement, usually one-off |
| **Measure** | What is the baseline, precisely? | A repeatable number |
| **Optimize** | Can this work be avoided or batched? | The change |
| **Verify** | Did behaviour stay identical? | Equivalence tests |
| **Guard** | Can this silently come back? | A regression test with a ceiling |
| **Validate** | Does it still hold next month? | CI, every run |
| **Document** | What did we learn? | Commit message + this directory |

Profiling and measuring are different activities and get confused constantly. **Profiling is
exploratory and expensive; measurement is cheap and repeatable.** You profile once to find the
problem, then build a measurement you can afford to run forever.

---

## 2. When to profile

Profile when you have a **symptom**, not a suspicion:

- A measured latency or throughput number that misses a target
- An alert that fired (see [`observability.md`](../observability.md))
- A scaling trigger from [`scaling-triggers.md`](../scaling-triggers.md)
- A concrete plan that depends on an unproven assumption

**Do not profile because code looks slow.** The standing rule is evidence before optimization, and
the reason is not process purity: unmeasured optimization reliably targets the wrong thing and
carries correctness risk for nothing. `docs/engineering/repository-audit-findings.md` §5 lists
performance items deliberately left alone for exactly this reason.

---

## 3. How to measure

### Marginal, not absolute

Measure at two input sizes and take the difference. Fixed setup — loading a user, their accounts,
their rules — appears in both and cancels out. What remains is the true per-unit cost.

```
marginal = (cost(2N) − cost(N)) / N
```

This is also what makes a guard durable. An absolute total needs updating whenever any unrelated
fixed query is added, and **a test that needs constant updating is one people update without
reading**.

### Query counts specifically

Hibernate's `Statistics` gives both numbers, and they catch different faults:

| Counter | Moves when |
|---|---|
| `getPrepareStatementCount()` | Any SQL is issued — including lazy-loading an association |
| `getQueryExecutionCount()` | A JPQL/HQL query runs — a repository method called in a loop |

Track both. A lazy `@ManyToOne` initialised per row moves only the first; a repository call in a loop
moves both.

`ImportQueryCountIT` is the worked example.

### Prefer the counter to the clock

Wall-clock time in a test is noisy, machine-dependent and flaky. Query counts are exact, stable
across machines, and usually the thing that actually scales. The import profile makes this point
against itself: its wall-clock figures were inflated ~50% by the SQL logging needed to collect them,
while its query counts were unaffected and were the finding that mattered.

Use timing for user-facing SLOs. Use counts for regression guards.

---

## 4. Avoiding stale measurements

**This is the failure mode that actually bites, and it is not hypothetical here.**

`import-pipeline-profile-2026-08-07.md` was produced by running two imports with
`org.hibernate.SQL: DEBUG` and parsing 56,818 log lines by hand. Good work, correct findings — and
**it was stale within thirty-nine minutes**, because its top recommendation was implemented the same
morning. Anyone reading it after 08:38 would have been told to fix something already fixed.

Nobody erred. A measurement that costs a manual log-parsing session to repeat is a measurement that
gets repeated approximately never, and a performance document that cannot cheaply re-verify itself
decays into folklore — confidently describing a system that has moved on.

**So: the deliverable of a profiling session is not a document. It is a test.**

- Profile by hand once, to find the shape.
- Immediately convert the finding into an automated measurement.
- Annotate the profile document with a pointer to the test, and treat the document as history.
- Print the numbers on every run, even when passing — that is when someone deciding whether an
  optimization is worth doing needs them.

---

## 5. Avoiding benchmark bias

A benchmark that accidentally avoids the cost it means to measure passes cheerfully forever.

**Real example.** `ImportQueryCountIT` generates a statement with *distinct* merchant descriptions
per row. Identical rows would be answered from the merchant alias cache, hiding merchant resolution
entirely — the test would have passed while the pipeline stayed N+1. The original profile used the
same technique deliberately (60 distinct descriptions across 200 rows) for the same reason.

Checklist before trusting a benchmark:

- **Does the fixture exercise the cold path?** Caches, first-encounter inserts, empty tables.
- **Is the input representative in shape,** not just size? Distinct values, realistic date spread.
- **Does the measurement scale with the thing you claim scales?** Run at two sizes; if the "per-row"
  cost does not double with the rows, it is not per-row.
- **Is anything measuring the measurement?** SQL logging, statistics collection and assertions all
  cost something.

---

## 6. Correctness verification

> **Optimize behaviour without changing business semantics.**

An optimization that silently changes behaviour is worse than no optimization, because it ships
confidence with the defect. On a financial platform the failure is not a slow page — it is a wrong
number in someone's ledger.

**Write equivalence tests: the same inputs through the old and new paths, asserting the same
answer.** Not "the new path returns something sensible" — the same answer.

### The trap that generalises

Batching usually means moving a comparison from SQL into Java, and **those two do not always agree**:

| Comparison | SQL (Postgres) | Java |
|---|---|---|
| `BigDecimal` equality | NUMERIC, by **value** — `486.0 = 486.00` | `equals` is value **and scale** — those differ |
| String equality | Collation-dependent | Exact, case-sensitive |
| `NULL` | Never equal to anything | `null.equals` throws; `Objects.equals(null, null)` is true |
| Ordering | Collation-dependent | Lexicographic by code point |

The `BigDecimal` case is not theoretical: `DuplicateIndex` would have silently stopped matching
duplicates whenever a stored row and a parsed row carried different scales — a CSV writing `486.0`
and a PDF writing `486.00`. Nothing would fail. Users would just stop being warned.

**Verify against the real database, not an in-memory substitute.** The divergence that matters is
between Java semantics and SQL semantics, and only a real Postgres shows it.

### Share the terminal logic

When a fast path is added beside a slow one, make both end in the same routine. `DuplicateDetector`
has two `findMatch` overloads that both terminate in one `describe()`, so the evidence a user sees
cannot depend on which path found it. Two implementations of the same explanation drift.

---

## 7. Regression guards

### Why ceilings exist

An optimization without a guard is temporary. The next person adding a feature to that loop has no
way to know a query belongs outside it, and nothing will tell them — the code still works, just
slower, and slower is invisible until it is severe.

### How a ceiling is chosen

> **The ceiling must sit strictly below `measured + smallest known regression`.**

Not merely above the measurement. This is the rule, and it exists because an earlier revision of
`ImportQueryCountIT` broke it: measured 3.00, ceiling 5.00, while the regression it explicitly named
in its own failure message — the rule lookup returning to the row loop — was worth exactly +2.00.
**It would have landed on 5.00 and passed.** A guard that names a regression it cannot catch is the
same false confidence as a guard that never runs.

Procedure:

1. Measure. Call it `M`.
2. Identify the cheapest realistic regression. Call it `R` — usually "the thing just batched goes
   back to per-item".
3. Set the ceiling strictly between `M` and `M + R`, leaving enough headroom above `M` for ordinary
   variation (a cache miss, a first-encounter insert) that it will not flake.
4. If no gap exists, the measurement is too noisy to guard — fix the measurement first.

A flaky performance test gets deleted, and then nothing measures anything. Headroom is not laziness.

### When to lower it

**Every time an optimization lands.** Lowering the ceiling is the moment an improvement stops being
a number in a commit message and becomes something the build defends. An optimization merged without
lowering the ceiling has not been made permanent.

### When to reconsider it

- **After an architectural change** — moving imports to async workers changes what per-row even
  means, and the old number may no longer be comparable.
- **When it flakes.** A ceiling that fails intermittently is measuring noise. Fix the measurement or
  widen the input; do not quietly raise the ceiling.
- **Never to make a red build green.** Raising a ceiling to accommodate a regression is deleting the
  guard with extra steps. If the regression is genuinely acceptable, say so explicitly in the commit
  message and record why.

---

## 8. Known N+1 patterns in this codebase

The shapes that have actually occurred, so they are recognisable next time:

| Pattern | Where it appeared | Fix |
|---|---|---|
| **Per-row config lookup** | `category_rules` fetched inside the row loop, 2.00/row | Load once per operation, pass in |
| **Per-row existence check** | Duplicate detection, one query per row | Index by a coarser key (date), match in memory |
| **Per-row entity resolution** | Merchant alias → merchant → learning, 3 queries/row | Batch by distinct key up front (outstanding) |
| **Collection without `@BatchSize`** | Admin user listings scaling with page size | `default_batch_fetch_size`, set in `application.yml` |
| **Re-scan per group** | `LayoutIntelligenceService.driftingLayouts()` re-queries per layout | Deferred — rarely-hit admin path, unmeasured |

The first two are fixed. The current import cost is **2.00 statements/row**, down from ~6.3.

**A note on the third.** A two-column projection was tried there, measured, and correctly reverted
for adding `findById` calls without a net win. That reversion is evidence, not failure — it is what
"measure after" is for, and the record of it is why the next attempt starts better informed.

---

## 9. Performance review checklist

Every optimization should answer these, in the commit message:

1. **What was measured?** Baseline number, and how it was obtained.
2. **What changed?** The mechanism, not the outcome.
3. **What improved?** After number, same method as the before.
4. **What could regress?** Name the specific failure.
5. **How is regression prevented?** The guard, and why its ceiling is where it is.
6. **What evidence supports the improvement?** Test names a reviewer can run.

If a change cannot answer 1 and 3 with numbers obtained the same way, it is not a performance
change — it is a refactor with a performance-sounding rationale.

---

## 10. What this methodology does not cover yet

Stated so nobody mistakes silence for coverage:

- **No CPU profiling standard.** Everything above measures database round trips. If parsing or
  normalisation carries real CPU cost, nothing here would see it.
- **No concurrency measurement.** All import measurements are single sequential requests. Contention
  under parallel imports is unmeasured.
- **No production telemetry feeding back.** `finora.worker.*` metrics exist and are exported, but no
  dashboard tracks statements-per-import over releases. Until it does, these numbers come from CI on
  synthetic input, not from production.
- **No SLOs.** Targets should come from production measurement rather than estimates, and there is
  no production measurement yet.
