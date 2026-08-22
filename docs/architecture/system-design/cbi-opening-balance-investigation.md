# CBI Opening Balance Claim — Investigation

Evidence-only investigation, no production code changed. Triggered by resuming Phase 2E after
2E.3's closure, checking the owner's remaining unverified 2E.4 framing
(`docs/project-management/plans/project-plan-v1.0.md`, §4d: *"CBI opening balance detected as
47.77"*) against the real corpus. Scope note: this document intentionally never quotes any literal
value from the real statement it was found on, per this project's standing "describe the shape,
never the literal value" discipline for real customer documents — every number in what follows is
described structurally, not reproduced.

## Result

| Question | Finding |
|---|---|
| Does Finora currently detect/display an opening balance for this document that looks like the tail digits of a larger comma-grouped number (the claimed shape)? | **No.** The account's `openingBalance` field is `null` today for this document — nothing is populated, correct or incorrect. |
| Is there a currency-string parsing bug that would produce this truncated shape from an Indian comma-grouped balance (e.g. a four-digit balance with one thousands comma)? | **No.** `CsvParser.parseNumeric` strips every comma unconditionally before parsing, regardless of grouping position — verified by tracing the document's own first transaction's balance cell through the parser step by step. |
| Is there a live code path that could set `openingBalance` from a transaction row's running-balance column? | **Yes** — `PdfPreviewGenerator.buildDetectedAccountInfo` → `BalanceSequenceResolver.resolve(...)`. Traced with this same document's own first balance value: it produces the full correct number, not a truncated one. |
| Why is `openingBalance` null for this document today, then? | `BalanceSequenceResolver`'s deliberate ambiguity-suppression design (Phase 2G, §4.3 of `balance-chain-ordering-design.md`): when a day's transactions can't be uniquely ordered, it returns no opening/closing balance at all rather than guess. That is what is happening here — a documented "no answer" outcome, not a wrong answer. |

## Method

Ran `PdfPipelineDiagnostic` against the real, unredacted CBI (Central Bank of India) savings PDF
from the real corpus (`~/Downloads/Bank statement/Savings accounts/`), on current `main` with 2G
already merged. The document's first transaction row carries a running balance formatted with
Indian comma-grouping (one thousands separator, two decimal places, a `CR` suffix) — structurally
exactly the shape that would produce the claimed truncated value if a parser split on comma and
kept only the trailing group. Traced that exact string, in that exact shape, through every parsing
stage that touches a balance cell in the PDF import path:

1. `CsvParser.parseNumeric` (`CsvParser.java:425-479`) — the shared amount/balance parser for both
   CSV and PDF paths. Its `TRAILING_CR`/`TRAILING_DR` regexes strip only an anchored suffix, then
   `.replace(",", "")` removes every comma unconditionally before `BigDecimal` parsing — correct for
   both Western thousands-grouping and Indian lakh-style grouping, and confirmed correct for this
   document's own balance shape by direct trace.
2. `PdfPreviewGenerator.buildDetectedAccountInfo` (`PdfPreviewGenerator.java:520-561`), which builds
   a `BalancePoint` per row via the same `parseNumeric` call (`:396-399`) and feeds them to
   `BalanceSequenceResolver.resolve(...)` (`:552-555`) — the one live path that could set
   `openingBalance` for this document. Same parser, same correct result when traced.
3. No test fixture, ground-truth JSON, or prior investigation doc anywhere in the repo asserts the
   claimed value for this document.

## Conclusion

**2E.4's claim does not match current reality and no mechanism was found that would produce it.**
The suspected root cause — a comma-grouping parse bug truncating a real balance down to its trailing
digits — does not exist in `CsvParser.parseNumeric`; it correctly strips commas regardless of
grouping position. The only live path that derives an opening balance for this document
(`BalanceSequenceResolver`) uses the same correct parser and, for this document specifically,
legitimately returns no value at all by design (ambiguity suppression, not a guess) — a `null`, not
a wrong number.

**Recommend closing 2E.4 as not reproduced**, same disposition as 2E.3 — carried forward as an
unverified hypothesis since the owner's original framing, and the first actual check against
evidence does not confirm it.

## What this is not evidence of

- **Not evidence that every balance value in this document is correct.** Only the specific
  claimed shape (a truncated, comma-mangled opening balance) was checked; a full row-by-row
  correctness audit of this document was not performed and is out of scope here.
- **Not a fix.** No production code was changed; there was nothing to fix — the parser traced
  correctly at every stage checked.
- **Not evidence about any other document.** This traces one document's own balance shape through
  the parser; it is not a claim that Indian comma-grouping is universally handled correctly across
  every bank format in the corpus.
