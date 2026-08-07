# Import Pipeline Profile — 2026-08-07

Measurement, not a proposal. No code was changed. This supplies the evidence that
`docs/engineering/import-pipeline-scaling-design.md` §8 flagged as missing: whether the ~16ms
per-row import cost is N+1 database access, and if so, where.

**It is. There are five distinct per-row query patterns, and they scale exactly linearly.**

---

## Method

- Stack: Postgres 16 (Docker) + backend on the `dev` profile, which already sets
  `org.hibernate.SQL: DEBUG` — so no instrumentation was added to the code.
- `POST /api/v1/import/csv/stage` with generated CSVs of 200 and 400 rows, 60 distinct merchant
  descriptions (so alias-cache behaviour is visible rather than masked by identical rows).
- The backend log was sliced at the request boundary and every `org.hibernate.SQL` statement
  parsed and grouped by verb + table.
- The 400-row run is the control: if a pattern is genuinely per-row, its count must double.

---

## Result

| Query | 200 rows | 400 rows | Per row |
|---|---:|---:|---:|
| `select category_rules` | 400 | 800 | **2.00** |
| `select merchant_aliases` | 260 | 400 | 1.00–1.30 |
| `select merchants` | 200 | 400 | 1.00 |
| `select merchant_category_learning` | 200 | 400 | 1.00 |
| `select transactions` | 200 | 400 | 1.00 |
| `insert merchant_aliases` | 60 | 0 | first encounter only |
| **Total statements** | **1,329** | **2,408** | **~6.0–6.6** |

Every per-row count doubled exactly with the row count. This is N+1, confirmed, not fixed setup
cost.

`merchant_aliases` fell from 1.30 to 1.00 per row on the second run because the 60 distinct
merchants already existed by then — which also confirms the 0.30/row insert is a first-encounter
cost, not a steady-state one.

### Timing caveat

The profiled runs measured ~23–25ms per row (200 rows in 5.0s; 400 rows in 9.1s), against ~16ms
per row measured in `E2E_TEST_REPORT.md` without SQL logging. **The difference is the logging
itself** — 200 rows produced 56,818 log lines.

Treat the wall-clock figures here as inflated and use the E2E numbers for throughput planning. **The
query counts are exact and unaffected by logging**, and they are the finding that matters.

---

## What each pattern is

1. **`category_rules` — twice per row, the standout.** A user's category rules are a small set that
   cannot change during an import. Loading them once and matching in memory turns 800 queries into
   1. This is the cheapest fix on the list and the largest single reduction.

2. **Duplicate detection — `select transactions` once per row.** Duplicate detection is inherently a
   set operation: one query bounded by the statement's date range, loaded into a hash set, then
   matched in memory. The current shape asks the database the same shaped question once per row.

3. **Merchant resolution — three queries per row** (`merchant_aliases`, `merchants`,
   `merchant_category_learning`). This is the path `MerchantNormalizationEngine`'s javadoc already
   documents, including a prior measurement of ~500 loads per 500-row statement. Note its warning:
   a two-column projection was tried here, measured, and **correctly reverted** for adding
   `findById` calls without a net win. Batch-load the distinct normalized descriptions up front
   rather than reshaping the per-row query.

---

## Projected impact

Removing all five reduces per-row database work from ~6.3 queries to approximately zero, replaced by
a handful of bounded up-front queries:

| | Queries for a 5,000-row statement |
|---|---:|
| Today | ~31,500 |
| Batched/cached | ~10–20 |

This supports — with evidence rather than assumption — the 10× per-row improvement that the scaling
design listed as its highest-severity unproven assumption. **That assumption can now be treated as
substantiated in direction**, though the realised factor still has to be measured after the change,
not predicted.

Consequences for the scaling estimates: at a 10× improvement, 50,000 uploads of 5,000 rows moves
from ~1.8 hours on 100 workers to roughly 11 minutes — and, more importantly, from ~46 CPU-days of
work to ~4.6. It also relieves the connection-pool pressure identified as the first practical
ceiling, since each import holds a connection for far less time.

---

## Recommended order

Ranked by ratio of impact to risk. Each is a batch-or-cache change, **none is architectural**, and
each should be measured before and after per the standing rule.

1. **Cache `category_rules` per import** — 2 queries/row → ~0. Smallest change, largest win, no
   shared state beyond one import's lifetime.
2. **Batch duplicate detection** — 1 query/row → 1 per import. Well-understood set operation.
3. **Batch merchant resolution** — 3 queries/row → 1–2 per import. Largest of the three and the one
   with a reverted prior attempt, so it needs the most care and the most measurement.

Protect correctness with tests before changing any of them: these paths decide categorisation and
duplicate suppression, where a silent regression corrupts user data rather than merely slowing
things down.

---

## What this does not establish

- **No CPU profiling.** This measures database round trips only. If parsing or normalisation carries
  significant CPU cost of its own, that is not visible here.
- **PDF is untested.** All measurements are CSV. PDFBox extraction may have a different profile.
- **No concurrency measurement.** Single sequential requests; contention behaviour under parallel
  imports is unmeasured.
- **The projected 10× is a projection.** It follows from the query counts, but the realised
  improvement must be measured after implementation.
