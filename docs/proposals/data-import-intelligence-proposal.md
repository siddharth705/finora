# Data Import Intelligence — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

**Major correction to the originating draft's premise:** it proposes building import job tracking,
error classification, and an admin health dashboard as new work, using a simplified state model
(UPLOADED/PROCESSING/COMPLETED/FAILED/RETRYING). **Roughly 80% of this already exists, and it's more
sophisticated than what the draft proposes** — building the draft's version would be a regression,
not an addition:

- `ImportJob.Status` (`entity/ImportJob.java`, migration V66) already has `QUEUED, PARSING,
  ANALYZING, DEDUPING, IMPORTING, LEARNING, COMPLETED, FAILED, CANCELLED` — one real state machine,
  finer-grained than the draft's five states. Retry isn't a separate status; a failed attempt returns
  to `QUEUED` with a backoff timestamp (`recordFailure`/`RETRY_SCHEDULED`), which is a more correct
  model than a standalone `RETRYING` state.
- Error classification already exists as **structured codes, not raw exception messages**:
  `ErrorCode.java` has `IMPORT_PDF_PASSWORD_REQUIRED`, `IMPORT_PDF_PASSWORD_INVALID`,
  `IMPORT_SCANNED_OCR_REQUIRED`, `IMPORT_NO_TRANSACTIONS_FOUND`, and more — directly matching the
  draft's `PDF_PASSWORD_REQUIRED`/`INVALID_FORMAT` examples, already persisted per attempt on
  `statement_analysis_sessions.failure_code`. Balance mismatch is handled by a *more* structured
  mechanism than a flat code: `import_verification_findings` with a `rule='BALANCE_CHAIN'` +
  `outcome` column (V72) — worth preserving that design rather than flattening it into one enum
  value as the draft's `BALANCE_MISMATCH` implies.
- Success/failure counts and a failure-reason histogram already exist as an API:
  `AdminStatementAnalysisController.summary()` → `AnalysisSummary` (parsed / failed /
  `unanchoredReasons` histogram). The draft's "Import Health" dashboard example is a real, working
  endpoint away from being a page — it isn't a backend design problem.

## 1. Objective

Close the two genuine gaps: an admin-portal page presenting the existing `/summary` data (nothing
today aggregates it visually — `ImportTrace.tsx` is a per-import lookup tool, `SystemHealth.tsx`
shows a raw recent-imports list, neither is the dashboard the draft describes), and a bank-identifier
field enabling the "top failures: SBI PDF format" breakdown the draft asks for, which doesn't exist
in any current table.

## 2. What exists today (baseline — see correction above for full detail)

- Job state machine, retry-with-backoff, structured error codes, per-attempt failure-code
  persistence, balance-verification findings table, worker metrics
  (`finora.worker.dead_letters`/`.duration`/`.queue_depth`), and trace-by-reference/by-job endpoints
  — all shipped.
- `import_jobs.source_format` / `statement_analysis_sessions.source_format` distinguish PDF vs. CSV
  only — no bank identifier column anywhere in the import pipeline's own tables.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Admin "Import Health" dashboard page (new — thin, over an existing endpoint)

A new `admin-portal/` page consuming `AdminStatementAnalysisController.summary()` as-is:

```
Import Health
Today: Successful 2,450 · Failed 37

Top failure reasons (from the existing unanchoredReasons histogram)
  SBI PDF format         18
  Password-protected PDF 10
  Invalid CSV              9
```

No new backend endpoint required for the counts themselves. If per-bank breakdown (§3.2) is added,
this page's histogram gains a bank dimension — same page, richer data source.

**Import quality metrics — added to the same page, not a separate one.** Success/failure counts
answer "is anything broken"; they don't answer "is import quality trending better or worse." A small
metrics row alongside the health counts, computed from data already recorded (no new capture needed):

```
Import Quality (7-day rolling)
  Avg. processing time        4.2s
  Avg. transactions extracted 47
  Duplicate rate                3.1%
  OCR usage rate                 8%
  Verification success rate    96.4%
```
`import_job_stages` (per-stage timing) and `import_verification_findings` (outcome per rule) already
carry what these need — this is a query/aggregation layer over existing tables, not new tracking.

### 3.2 Bank identifier on import records (new — small schema addition, with explicit confidence)

Add a `bank_id`/`bank_code` column to `statement_analysis_sessions` (and by extension `import_jobs`
via its existing join to the session) so failures can be grouped by bank, not just format. Small
addition given `BankRegistry`/`BankManagementService` already exist and are used elsewhere
(`TransactionService`'s search already resolves bank names via this registry, per the Search
proposal's findings) — this reuses an existing lookup, not a new bank-modeling effort.

**A nullable `bank_id` alone is ambiguous — it can't distinguish "detection wasn't attempted" from
"detection was attempted and failed" from "detection was ambiguous between two banks."** Those are
different operational signals (the second is a real gap to investigate, the first isn't). Add a
status alongside the identifier rather than relying on null to carry that meaning implicitly:

```
bank_identification_status  — IDENTIFIED, UNKNOWN, AMBIGUOUS
bank_id                      — set only when status = IDENTIFIED
```

`UNKNOWN` (attempted, no match) and `AMBIGUOUS` (matched more than one bank's layout fingerprint) are
both worth their own row in the failure-reason histogram (§3.1) rather than collapsing into a single
"no bank" bucket — they point at different fixes (add a new layout vs. disambiguate an existing one).

### 3.3 Parser version tracking (new — small column, real debugging value)

`statement_analysis_sessions.parser_version` (or equivalent, whatever identifies the specific
layout/parser revision that processed a given upload — e.g. `SBI_PDF_V2`). Without this, "why did my
old import look different from a new one for the same bank" has no answer once a parser is updated —
the session row exists, but nothing on it says which version of the logic produced it. Cheap to add
now (a string column populated at parse time from whatever the layout registry already resolves),
expensive to reconstruct retroactively once several parser versions have shipped without it.

### 3.4 Import event timeline (presentation only — no new backend)

`import_job_stages` already records per-stage timing (§3.1's quality metrics reuse the same table).
Surface it as an ordered timeline in the existing `ImportTrace.tsx` per-import lookup page, rather
than only the current summary view:

```
Import Timeline — SA-20260812-0145
10:01  File uploaded
10:02  PDF parsed
10:03  Transactions extracted
10:04  Duplicates checked
10:05  Completed — 156 transactions added
```

This is a rendering change to an existing admin page over an existing table (`import_job_stages`),
not a new trace mechanism — `AdminImportTraceController`'s endpoints already return this data; the
gap is presentation, same category of gap as §3.1's dashboard.

### 3.5 User-facing import status (explicitly future, not v1)

Today's import status is admin/trace-only. A user-facing equivalent ("SBI July Statement — ✓
Completed, 156 transactions added" / "⚠ Action required — password protected PDF") is a natural
follow-on once §3.1–§3.4 exist, since it would read from the same underlying data. Not designed
further here — flagged so it isn't lost, not scoped as v1 work.

## 4. Explicitly out of scope

- Rebuilding job tracking, error classification, verification findings, or trace endpoints — done,
  and better designed than what the original draft proposed.
- Flattening `import_verification_findings`'s rule/outcome model into a single error-code enum —
  the existing design is more expressive; don't regress it to match the draft's simpler example.
- Any Fino "why are my transactions missing" integration — consumes the existing trace/summary
  endpoints once Fino is unparked; no Fino work here.
- User-facing import status (§3.5) — flagged as a natural follow-on, not built now.

## 5. Estimated effort

| Component | Effort |
|---|---|
| ~~Import job tracking~~ | Already built |
| ~~Error classification~~ | Already built |
| ~~Trace endpoints~~ | Already built |
| Admin Import Health dashboard page | S |
| Import quality metrics (§3.1, query layer over existing tables) | S |
| Bank identifier + identification-status columns + lookup wiring | S–M |
| Parser version tracking column | S |
| Import event timeline (presentation over existing trace data) | S |

Far smaller than the original draft implied — this is a UI page, a rendering addition, and a handful
of schema columns, not a platform.

## 6. Open questions for whoever implements this

- Backfill strategy for `bank_id`/`bank_identification_status` on historical rows — derive from
  existing layout-fingerprint data where possible, or leave historical rows `UNKNOWN` rather than
  guessing?
- Backfill strategy for `parser_version` on historical rows — likely infer from the layout registry's
  own version history where recoverable, otherwise leave null rather than guessing a version.
