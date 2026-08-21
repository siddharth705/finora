# Marker-Row Pollution — Production Scope Investigation

Evidence-only investigation, per PM instruction. No production code touched. Follow-up to
C-8.1/C-8.2 (`docs/architecture/system-design/c8.1-c8.2-class-two-fixture-and-trigger-evaluation.md`),
which found that `OPENING BALANCE`/`CLOSING BALANCE` marker rows in the project's own golden PDF
fixture get staged as ordinary transaction rows and poison `BALANCE_CHAIN`/`STATEMENT_TOTALS`
verification. That investigation was scoped to a test-only composite trigger; this one asks
whether the same pollution reaches the real, already-shipped pipeline.

## Scope verdict (up front)

**(a) — a real production bug, currently live, on both the PDF and CSV import paths, whenever a
source statement prints an explicit `OPENING BALANCE` / `CLOSING BALANCE` label row that carries a
real (non-blank) date.** It is not a test-fixture artifact. It is not masked by anything structural
— only accidentally avoided on documents whose marker rows happen to have a blank date column. It
affects both the `VerificationReport` shown on the review screen (false `WARNING`/`FAILED` on
correctly-parsed statements) and, separately and more seriously, the actual persisted transaction
list: an `OPENING BALANCE`/`CLOSING BALANCE` row can be imported as a real `EXPENSE` transaction
whose amount equals the account's entire opening/closing balance, unless the user notices and
manually unchecks it during review (nothing distinguishes it programmatically). No existing test
exercises the full stage→confirm path for this shape, so nothing currently protects against the
transaction-list impact.

## 1–2. Where it happens, and is there already a filter

`com.finora.imports.TransactionNormalizer.normalize()` (`backend/src/main/java/com/finora/imports/TransactionNormalizer.java`)
is the single place a raw ledger row becomes a `StagedRow`. It has **no marker-row exclusion of any
kind** — no check on description text, no "is this a balance-only label row" guard. Worse, its
`AMOUNT_HINTS` list deliberately includes `"balance"`/`"running balance"`/`"closing balance"` as a
**last-resort amount fallback**, specifically so that a label row with no debit/credit value (only a
balance figure) does not get silently dropped (see the class's own doc comment, lines 32–39). That
is: the normalizer is intentionally designed to stage these rows as transaction-shaped data, on the
theory that "never lose information" is more important than filtering — but nothing downstream ever
un-does that decision before the row reaches verification or persistence.

Both staging entry points feed the unfiltered list straight into `ImportVerifier.verify()`:

- PDF path — `PdfPreviewGenerator.buildProductSections`/section-building code
  (`backend/src/main/java/com/finora/imports/pdf/PdfPreviewGenerator.java:314-363`): every row in
  `section.rows()` is normalized and added to `staged` with no filter; `documentOrder` (line 348) is
  just `List.copyOf(staged)`; line 359 calls
  `importVerifier.verify(documentOrder, ...)` — the **exact list that includes marker rows**. The
  same `staged` list is what `StagedAccountSection` returns to the caller (line 363) as the review
  table's actual row set.
- CSV path — `PreviewGenerator.java:102-127`: identical shape. `staged` is built with no filter and
  handed to `importVerifier.verify(staged, ...)` at line 125.

`ImportVerifier.verify()` (`backend/src/main/java/com/finora/imports/ImportVerifier.java:69-79`)
runs `BalanceChainValidator`, `StatementTotalsValidator`, `SummaryTotalsValidator`, and
`ColumnAmbiguityValidator` directly over whatever `rows` it is given. It has no filter either — it
trusts its caller.

**Why the test fixture "still exhibited pollution": it isn't a test-detection mismatch — it's an
accurate demonstration of the real pipeline's own behavior.** The project's own regression test
proves this is by design, not accident:

`backend/src/test/java/com/finora/imports/pdf/PdfPreviewGeneratorTest.java:68-77`
(`generate_extractsAllSixRowsFromTheGoldenFixture`) asserts `response.rows()).hasSize(6)` and its
comment says outright: *"OPENING BALANCE, 4 real transactions, CLOSING BALANCE -- all 6 rows have a
parseable date and amount ... so all 6 should survive `TransactionNormalizer.normalize()` the same
way a CSV opening/closing-balance row would."* This is the real `PdfPreviewGenerator`, called with
real validators, producing the real `StagingResponse` a real import would produce for this file
shape.

There is exactly one place marker rows currently get dropped, and it is incidental, not a
deliberate filter: `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java:718-747`
(`parseAndStage_recognizesCurrencySuffixedHeaders_andSkipsOpeningClosingBalanceRows`) uses a CSV
fixture where the `OPENING BALANCE`/`CLOSING BALANCE` rows have a **blank Date cell** — those rows
drop out only because `TransactionNormalizer.normalize()` requires a parseable date, not because of
anything that recognizes them as markers. Whether a real statement's marker row happens to carry a
date is a property of the bank's own layout (the PDF golden fixture's marker rows do carry the
statement's start/end date), so this "protection" is data-shape luck, not a guard.

Two places DO special-case marker rows, but only for **balance derivation math**, not for keeping
them out of the transaction stream:
`PdfPreviewGenerator.buildDetectedAccountInfo`'s `isExplicitOpeningRow` (line 482-486) and
`StatementValidator.buildDetectedAccountInfo`'s identical logic (line 146-150) both check whether a
row's description contains `"opening balance"` — but only to decide whether to back out a signed
amount when computing the account's opening balance. Neither removes the row from `staged`,
`documentOrder`, or what gets sent to `ImportVerifier` or persisted.

## 3. Does it reach actually-persisted transactions, not just verification signals

Yes. `ImportService.persistSection()` (`backend/src/main/java/com/finora/imports/ImportService.java:704-777`)
iterates `request.rows()` (the `ConfirmedRow`s the client sends back) and persists every row whose
`row.include()` is `true` as a real `Transaction` — there is no marker-row check at this layer
either. Whether a marker row is included is controlled entirely by the frontend's default:
`frontend/src/lib/importReview.ts:60-66` (`beginReview`) sets
`included: rows.map((row) => !isUnderReview(row))`, and `isUnderReview` (line 51-53) is `true` only
when `row.duplicateMatch != null`. A balance-marker row is not a duplicate, so it defaults to
**included = true** — it will be persisted as a real transaction unless the user notices the
`OPENING BALANCE` / `CLOSING BALANCE` text in the description column and manually unchecks it.
Nothing in `StagedRow`/`ConfirmedRow` carries any signal that would let the UI flag or default-
exclude it automatically; `grep` for any marker/summary-row flag (`isBalanceMarker`,
`excludeFromImport`, `isSummaryRow`, etc.) across both `backend/src` and `frontend/src` returns
nothing.

Concretely, for the project's own golden PDF fixture, confirming all six staged rows as-is would
create two extra `EXPENSE` transactions: one dated at the statement's opening balance point for
50000.00, and one at the closing balance point for 117209.50 (or similar) — doubling as fake expense
activity on top of the four real transactions.

## 4. Does it affect balance/totals calculations

Yes, for verification, not for the account balance itself:

- `BalanceChainValidator` and `StatementTotalsValidator`, called via `ImportVerifier.verify()` with
  the unfiltered row list, see the marker rows as ordinary transactions. On the golden fixture this
  produces `BALANCE_CHAIN = WARNING` and `STATEMENT_TOTALS = FAILED` on a statement that is in fact
  perfectly correct (this is Finding A from the C-8.1/C-8.2 report, now confirmed to run through the
  real, non-test call path).
- The account's actual stored balance (`AccountBalanceConvention` / `ImportService`'s closing-
  balance-write logic) is derived from the statement's own detected `openingBalance`/
  `closingBalance` fields (via `buildDetectedAccountInfo`'s balance-point logic), not by summing
  staged transaction amounts — so the marker rows do not directly corrupt the account balance
  figure shown elsewhere in the app. They corrupt (a) the verification panel, and (b), if a user
  fails to uncheck them, (b) the transaction list and category/spend totals, by adding two fake
  large-amount `EXPENSE` entries.

## 5. What currently protects against this

Nothing structural. Specifically:

- No test exercises `ImportService.confirm()`/`persistSection()` end-to-end on a statement whose
  marker rows carry a real date, so nothing currently asserts that a marker row does NOT become a
  persisted `Transaction`.
- `ClosingBalanceEvidenceVerticalSliceTest` (`backend/src/test/java/com/finora/imports/evidence/`)
  works around the exact same pollution by filtering to `realTransactionRows` before calling the
  validators directly — i.e., it's a test that already knows to avoid feeding raw `staged` rows
  into the validators, but that filtering exists only inside that one test's own harness, not in
  any production class it is testing.
- `PdfPreviewGeneratorTest.generate_extractsAllSixRowsFromTheGoldenFixture` documents the pollution
  but only asserts the row *count*; it does not assert anything about `response.verification()`, so
  it does not currently catch the false `WARNING`/`FAILED` this produces.
- The one test that "skips" marker rows (`ImportServiceAskOnceTest`) does so via a fixture-specific
  accident (blank date column), not a general guard, and does not generalize to the PDF path or to
  any CSV export that dates its balance rows.

## Summary of code paths involved

| Concern | Class / method | File |
|---|---|---|
| Row → StagedRow, no marker filter | `TransactionNormalizer.normalize()` | `backend/src/main/java/com/finora/imports/TransactionNormalizer.java` |
| PDF staging, unfiltered list to verifier | `PdfPreviewGenerator` (section build loop) | `backend/src/main/java/com/finora/imports/pdf/PdfPreviewGenerator.java:314-363` |
| CSV staging, unfiltered list to verifier | `PreviewGenerator.generate()` (or equivalent loop) | `backend/src/main/java/com/finora/imports/PreviewGenerator.java:102-127` |
| Verification, trusts caller's row list | `ImportVerifier.verify()` | `backend/src/main/java/com/finora/imports/ImportVerifier.java:69-79` |
| Confirm/persist, no marker filter | `ImportService.persistSection()` | `backend/src/main/java/com/finora/imports/ImportService.java:704-777` |
| Default "include" state, no marker awareness | `beginReview()` / `isUnderReview()` | `frontend/src/lib/importReview.ts:51-66` |

This is a scope/impact finding only. No fix is proposed or implemented here, per the PM's explicit
instruction; a fix (likely a real marker-row detection/exclusion step shared by both staging paths,
applied before verification and before the row is offered to the user as includable) is a separate,
narrowly-scoped follow-up decision.

---

## Ticketed as BH-060 (2026-08-16)

Formally assigned bug-hunt ID `BH-060`, filed OPEN in `docs/project-management/plans/project-plan-v1.0.md`
§4 (P1) and as [issue #138](https://github.com/siddharth705/finora/issues/138) with the `bug`
label — see the plan for the current status and the issue for discussion/assignment. Severity:
financial correctness, live in production on both import paths, no existing protection.
