# Financial Document Intelligence — Evidence Registry

Real documents are never committed (see the "Handling real documents" steps in
[financial-document-intelligence-principles.md](financial-document-intelligence-principles.md) —
copied to a gitignored scratch path, debugged interactively, deleted). What's supposed to survive
instead is the *capability* the document motivated — code plus a regression test. This registry
is the missing piece between those two: a record of what each real document actually **taught**
the engine, independent of whether that lesson became a shipped capability, a documented
limitation, or an open investigation. It's the institutional memory the Capability Registry alone
doesn't capture, because the Capability Registry is organized by capability, not by document — you
can't currently answer "what did we learn from the last Canara statement" by reading it.

**Privacy:** entries below identify documents by **issuing bank and document type only** — never
by account holder name, account number, or any other value that came from the real file. That
matches every other rule in this codebase about real documents (see "Testing philosophy" and
"Handling real documents" in the principles doc): the lesson survives, the file's actual contents
don't. If you're adding an entry and the only way to describe the evidence is to quote something
identifying, generalize the description instead (e.g. "a bare single-word label with no colon,"
not the literal name that followed it).

**When to add an entry:** any time a real document is reviewed against the pipeline, whether or
not it changes any code — a document that confirms existing behavior with zero surprises is itself
useful evidence (it's what eventually lets a capability's "Evidence" count grow toward Phase 5's
gates) and still gets a row here, with an `Outcome` of "No new evidence — confirms existing
capabilities."

---

## Evidence Metrics

A snapshot, not a delta — this is the **Cycle 1 baseline**. Every number below is directly
countable from this registry and the principles doc as of the date shown, so it can be
regenerated rather than trusted from memory. Future cycles should report deltas against the prior
cycle's snapshot, not against this baseline forever.

**As of 2026-08-02 (Evidence Cycle 1):**

| Metric | Count | Source |
|---|---:|---|
| Real documents reviewed | 8 | Entries below (7 read successfully, 1 unreadable — environment gap) |
| Capabilities in the Capability Registry (stable/beta) | 15 | `####` entries under "Capability Registry" in the principles doc |
| Capabilities extended this cycle | 4 | `ACCOUNT_HOLDER`, `GRID_METADATA_FALLBACK`, `LEADING_NAME_LINE`, `TransactionNormalizer` reference-column hints |
| New capabilities created this cycle | 4 | `GRID_METADATA_TRAILING_LABEL`, `LEADING_NAME_LINE`, `LEADING_NARRATION_CONTINUATION`, `STATEMENT_CLOSING_MARKER` (page-boundary variant) |
| Capabilities deferred (backlog) | 5 | Rows in the "Capability Backlog" table, principles doc |
| Regression tests (backend, total) | 741 | `mvn test` — verified this session |
| Regression tests (frontend + admin-portal, total) | 10 | `client.test.ts` in each app (5 cases each) |
| Synthetic fixtures (`PdfFixtureBuilder`) | 15 | `build*Sample(...)` methods |
| Documented known limitations | 14 | `**Known limitations:**` entries across the Capability Registry, excluding "N/A — not yet attempted" |
| Open investigations | 1 | HDFC credit-card table over-extension (2/40 rows parsed) |

This table is deliberately not automated (no script generates it yet) — recomputing it by hand at
each cycle boundary is cheap enough for now, and automating a metric before it's been useful even
once would be exactly the kind of premature infrastructure "Build data before dashboards" warns
against.

---

## Documents

### Composite multi-account savings statement
- **Institution / type:** a composite statement bundling multiple account sections in one PDF
  (the shape `COMPOSITE_STATEMENT`/`MULTI_ACCOUNT` was originally built from).
- **Evidence:**
  - Multiple account sections detected in one document, each independently staged.
  - Offset column anchors (columns not aligned to a single consistent x-position across rows).
  - Wrapped multi-line transaction descriptions.
  - Bare and parenthesized Dr/Cr amount suffixes.
  - A populated running-balance column, reconstructable into opening/closing balance per section.
  - Account holder name, account number, branch, and IFSC all absent from this document's
    pre-table text in a form any current metadata pattern recognizes — re-confirmed during this
    cycle's validation pass, not yet root-caused.
- **Outcome:**
  - ✓ `COMPOSITE_STATEMENT`/`MULTI_ACCOUNT`, `OFFSET_COLUMN_ANCHORS`, `WRAPPED_DESCRIPTION`,
    `DR_CR_SUFFIX`, `RUNNING_BALANCE` — all already-stable capabilities, re-validated with no
    regressions this cycle.
  - ✗ Account-level metadata gap not yet root-caused — no backlog entry filed yet; needs its own
    diagnostic pass before one is (see "Evidence before capability" — a description this vague
    isn't itself evidence of a specific structural pattern).

### Axis Bank Neo Rupay credit card statement
- **Evidence:**
  - Account holder's plain name as an unlabeled early pre-table line (2nd confirming document
    for this shape, after the composite statement above showed a related-but-distinct gap).
  - A multi-column Payment Summary grid: label row, then a later value row, with Payment Due Date
    and Credit Limit both present as columns among several others on the same row.
  - `"**** End of Statement ****"` boilerplate text on its own that was, before this cycle,
    getting merged into the preceding real transaction's description instead of excluded.
  - A 108-row transaction table with no pagination in the review UI (classified as UI work, not
    an engine capability — see the "UX priority order" decision made earlier this project).
- **Outcome:**
  - ✓ `LEADING_NAME_LINE` created (initial line-0-only version).
  - ✓ `GRID_METADATA_FALLBACK` extended to the multi-column grid shape (`GRID_DUE_DATE_LABEL`
    widened from "ends with" to "contains," `GRID_CREDIT_LIMIT_LABEL`, `AMOUNT_LIKE`,
    `DATE_RANGE_MEMBER` exclusion).
  - ✓ `STATEMENT_CLOSING_MARKER` created (`PAGE_BOUNDARY_ISOLATION` family).
  - → Pagination logged as UI backlog, not engine work — no capability entry, by design.

### Union Bank of India savings statement
- **Evidence:**
  - A two-column account-details grid where each row is `<value> <label>` — the reverse of every
    ordinary "Label: Value" line — for account number, IFSC, account holder name, and statement
    period.
  - Account holder name specifically wrapped across multiple lines *before* its own label
    appeared, a still-unhandled harder variant of the same reversed shape (documented as a known
    limitation, not attempted).
  - A masked account number that displayed as mojibake in a Windows terminal during this cycle's
    validation pass — investigated and confirmed to be a **terminal rendering artifact only**; the
    actual stored value is correct. Recorded here so the same false alarm isn't re-investigated
    from scratch next time this document (or one like it) is reviewed.
  - Every transaction row's narration lands under a `"Transaction Id"` key instead of the
    `"Remarks"` key the header row itself detects — a column-anchor artifact specific to this
    document (the header token and the actual data values don't share a bucketing anchor).
    `TransactionNormalizer` never read `"Transaction Id"` as a possible description source, so
    every row staged with an empty description, which then also broke categorization (nothing to
    match against) and silently marked every row "low confidence" in the review UI — reported
    directly by the user from a live import, not found via investigation.
- **Outcome:**
  - ✓ `GRID_METADATA_TRAILING_LABEL` created (`ACCOUNT_NUMBER_TRAILING_LABEL`,
    `ACCOUNT_NAME_TRAILING_LABEL`, `IFSC_SHAPE`, `STATEMENT_PERIOD_TRAILING_LABEL`).
  - ✓ `TransactionNormalizer.DESCRIPTION_HINTS` extended with `"transaction id"` as a last-resort
    fallback (only used when every real description column is absent or blank), with a regression
    test confirming it never overrides a genuinely populated `"Remarks"`/`"Description"` column.
  - ✓ Re-validated this cycle with no regressions.
  - ✓ Mojibake display investigated and closed as a non-issue (not a capability change).

### Canara Bank e-passbook
- **Evidence:**
  - `LEADING_NARRATION_CONTINUATION`'s original motivating shape: multi-line transaction
    narration both before and after the date-bearing row, sometimes both for the same
    transaction.
  - A bare single-word account-holder label with no colon, immediately followed by the value on
    the same line — a phrasing none of `ACCOUNT_HOLDER`'s existing label synonyms covered, and
    which (before this cycle's fix) fell through to `LEADING_NAME_LINE` and wrongly captured the
    label word itself as part of the name.
  - A reference/cheque number embedded inside free-text transaction narration rather than
    appearing in its own column.
- **Outcome:**
  - ✓ `LEADING_NARRATION_CONTINUATION` created (prior cycle).
  - ✓ `ACCOUNT_HOLDER` extended with the bare-label synonym this cycle; `LEADING_TITLE_WORDS`
    extended defensively.
  - ✓ Regression tests added; Capability Impact Report filed (see the principles doc).
  - → Embedded narration reference numbers logged to the Capability Backlog (Medium priority,
    deferred — needs free-text mining, a materially different mechanism from column extraction).

### Kotak Mahindra Bank savings statement
- **Evidence:**
  - A reference-number column header phrased with internal punctuation
    (`normalizeHeaderCell`'s existing trailing-parenthetical-only stripping didn't already cover
    this exact variant), holding real values `TransactionNormalizer` was previously discarding.
  - Account holder name appearing on the third pre-table line, past a generic document-title line
    and a date-range line — the second confirming document that `LEADING_NAME_LINE` needed to
    look beyond line 0.
- **Outcome:**
  - ✓ `TransactionNormalizer.REFERENCE_HINTS` extended with the new header variant.
  - ✓ `LEADING_NAME_LINE` widened beyond line 0 to a bounded search window, with
    `LEADING_TITLE_WORDS` added as the corresponding false-positive guard.

### HDFC Tata Neu Plus credit card statement
- **Evidence:**
  - A whole-rupee-amount Credit Limit value with no decimal places at all, on a multi-column
    payment-summary grid.
  - That same grid's header line also satisfying the *ordinary* same-line `CREDIT_LIMIT` label
    pattern, whose greedy trailing capture silently swallowed non-numeric text as if it were the
    value, permanently skipping the line before the grid fallback ever got a chance to run.
  - A composite account-holder line: `<card number> <trailing label for the number>
    <unlabeled name>`, all on one line — a shape none of the three existing account-holder
    patterns cover.
  - A payment-summary grid whose label and value rows are genuinely scrambled: the credit-limit
    label splits across two non-adjacent lines, an unrelated row's values sit between the label
    and its own value, and the value rows themselves arrive in reversed order relative to their
    labels.
  - A currency amount rendered with a glued, non-standard character in place of the Rupee symbol
    — already handled elsewhere (`CsvParser.parseNumeric`), re-confirmed as NOT the actual
    blocker for the scrambled grid above.
  - Only 2 of 40 raw bucketed table rows survived normalization — traced to the transaction table
    never being recognized as "ended," so trailing content (a rewards-points table, T&C bullets,
    a GST entry table, a links table, the digital-signature block) all got bucketed as more rows
    of the same table.
- **Outcome:**
  - ✓ `AMOUNT_LIKE` widened to accept whole-number amounts.
  - ✓ Same-line `CREDIT_LIMIT`/`PAYMENT_DUE_DATE` greedy-capture bug fixed (only commit when the
    captured text actually parses).
  - → Composite account-holder line logged to the Capability Backlog (Low priority, deferred) with
    a regression-guarded test asserting today's correct null.
  - → Scrambled/split credit-summary grid logged to the Capability Backlog (Low priority,
    deferred) with a regression-guarded test — a naive fix was verified to produce a *wrong* value
    (₹200 instead of ₹78,000), so null is the intentionally-preserved correct result.
  - ✓ Investigation completed and documented (Open Investigation, High priority) for the 2/40
    parse-rate gap — root cause identified as a table-detection pipeline gap, deliberately **not
    fixed** pending a second real document.

### PNB ONE statement
- **Evidence:**
  - "Customer Name:" as the account-holder label, rather than any "Account Holder" phrasing —
    otherwise a layout `GRID_METADATA_TRAILING_LABEL`/ordinary `ACCOUNT_HOLDER` already covered.
- **Outcome:**
  - ✓ `ACCOUNT_HOLDER` extended with the "Customer Name" synonym (prior cycle).

### Unreadable statement (11-page PDF)
- **Evidence:** none extracted — the file could not be rendered in this environment (`pdftoppm`,
  a dependency of the PDF-processing toolchain used for interactive debugging, is missing from
  the sandbox). This is an environment gap, not a parser gap.
- **Outcome:** excluded from this cycle's validation. Revisit once the environment dependency is
  available — no code or documentation change was possible or appropriate here.
