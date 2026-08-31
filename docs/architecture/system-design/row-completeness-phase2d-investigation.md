# Row Completeness — Phase 2D Investigation

Evidence-only investigation, per D-29 (`docs/project-management/plans/project-plan-v1.0.md`, §11 and
§4d). No production code changed. Scope: does every real transaction in the corpus become a staged
row? Companion to `transaction-boundary-phase2a-investigation.md` (2A/2B/2C, the opposite question —
does non-transaction content stay OUT).

## Method

Cross-checked every document in the real corpus that has a committed ground-truth JSON
(`~/Downloads/Bank statement/ground-truth/*.json`) against a fresh `CorpusProbe` run on current
`main` (post Phase 2C), comparing the ground truth's summed `expectedTransactions` against the
pipeline's actual staged row count. 21 documents have ground truth; the real corpus has more
documents than that (some, like the header-reconstruction-broken ones below, have never had ground
truth established — see "Not covered by this cross-check").

## Result: 20 of 21 ground-truthed documents match exactly

| Document | Expected | Observed | Match |
|---|---|---|---|
| AU Credit card, Axis credit, BOB, Bandhan bank, CBI, HDFC 3 month, HDFC credit, HDFC sav, HSBC, ICICI CC, ICICI saving, Kotak CC, Manas_HDFC, Mann HDFC, PNBONE_STMT, Sanjay HDFC, Sanjay SBI, Union Bank, canara, new kotak | (per-document) | (per-document) | **OK, all 20** |
| Shivani_HDFC | 81 (75 + 6) | 75 | **MISMATCH — see below, not a Phase 2 bug** |

## The one mismatch: Shivani_HDFC, and why it's out of scope

The ground truth for this document is not naive — it already documents the gap and names it
explicitly: *"THE Shivani defect this codebase's own ground-truth design doc uses as its motivating
example, now confirmed as a real instance."* The recurring-deposit section has two parts: a
summary/terms block (no ledger) and a genuinely tabular "latest installment details" block (6 real
per-installment rows: sequence number, due date, amount paid, running installment-paid-to-date,
status, running balance). Today the RD section is correctly classified (`RECURRING_DEPOSIT`, 0.95
confidence) but its installment table extracts 0 rows.

This is not a Phase 2 bug to fix. `docs/architecture/system-design/ground-truth-model-design.md` §1
states the constraint directly: this document is **the reason RD/FD extraction has a governing
design doc in the first place**, and that doc's own header says *"Precedes RD/FD extraction... which
land together"* and implements ADR-005, whose §10 **forbids implementing RD/FD extraction before the
ground-truth model itself exists**. The ground-truth model is design-only today (no implementation).
Building an RD-installment-table extractor to close this gap would be exactly the kind of premature
M3 implementation ADR-005 exists to prevent. Confirmed via `git grep` that no RD/FD extractor exists
anywhere in `backend/src/main/java` today — this is genuinely unbuilt, not a regression.

**Verdict: correctly deferred already, by an explicit prior architectural decision. Not touched.**

## Not covered by the cross-check: three documents with no ground truth, one already-known defect

Three real documents have never had ground truth established, all hitting the *same* mechanism —
`HeaderReconstructionFinding` (`TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN`): a wrapped/multi-line
header collapses to a narrow 2-column fallback (e.g. `[Date, Amount]` instead of the real 5-6
columns), so every data row in that section fails to bucket its Description/Debit/Credit/Balance
values and gets dropped at `TransactionNormalizer`'s Stage 4 — an entire section's worth of real
transactions silently reduced to zero staged rows, with only an internal, evidence-only finding
(never surfaced to the user) marking that anything went wrong.

- **SBI Credit Card.PDF, Section 1** (a supplementary cardholder) — re-confirmed live via
  `PdfPipelineDiagnostic`: `Detected table columns: [Date, Amount ( \`)]`, 30 raw bucketed rows, **all
  30 dropped** at Stage 4. The 08-18 reliability matrix narrated this section as "27 real
  transactions" lost; the real count is unreconciled (no ground truth exists to adjudicate 27 vs.
  30), but the structural fact — one real supplementary-cardholder section, wholesale-lost — is
  confirmed unchanged.
- **Statement.pdf** (Indian Overseas Bank, misdetected as SBI) — per the reliability matrix, the
  identical mechanism, ~15 real transactions lost. Not re-verified in this pass (out of this
  investigation's time budget); flagged for a follow-up re-check before being treated as still
  accurate.
- **HSBC DB.pdf** — same mechanism, reached via the OCR acquisition path rather than native
  extraction (per the reliability matrix's 08-18 OCR-5 update). Also not re-verified this pass.

This is a real, evidenced, still-live row-completeness gap — squarely what Phase 2D asks about. It
is **not attempted here**. `C-8.3` (the project's own OCR-routing-trigger investigation, tracked in
the living plan's §4a) already tried and explicitly rejected a composite structural trigger for a
related question, citing a missed real failure class and three false-positive mechanisms, and still
lists 5 open questions. Header-reconstruction repair is a materially harder problem than the
trailing-content-marker pattern 2A-2C used (a single wrong signal here doesn't just leak a few extra
rows, it can corrupt the header itself for an entire section), and this investigation did not
attempt to re-derive that already-hard problem from scratch. Recorded as confirmed-still-live, not
re-solved.

## A fifth trailing-content marker was evaluated for SBI's own near-miss — and rejected

SBI Section 0 (the primary cardholder) shows the exact same accidental-Stage-4-rejection near-miss
pattern 2A/2B/2C already found and closed on Axis/Kotak/ICICI: 32 raw bucketed rows, only 30 survive
to staging, with the 2 accidentally-excluded rows being trailing boilerplate ("Transactions
highlighted in grey color, if any, do not form part of Purchases & Other Debits..." plus an
"Important Messages" section) that happens to fail `TransactionNormalizer`'s date parse rather than
being structurally excluded.

A candidate marker (`transactions highlighted in grey color`) was found, confirmed single-occurrence
in this document and absent from the rest of the real corpus, and implemented following the exact
`TRAILING_CONTENT_TRIGGERS` pattern 2A-2C established. **It was reverted before commit**: verification
against the real document showed it reduced `sections` from 2 to 1 — it did not just close Section
0's trailing content, it suppressed the entire document from that point forward, **deleting Section
1 (the supplementary cardholder) outright**, not just its already-broken 0 rows.

This is the exact case `PdfTableLocator`'s own design comment already named as a known limitation:
*"Not a resume-on-next-marker state machine: on every real document either trigger exists for, real
content never resumes once it fires... If a future real document needs resumption, that is new
evidence to design against, not something to guess at now."* SBI is that document. Unlike Axis,
Kotak, and ICICI (each a single real section, where "nothing genuine follows this line" is true for
the whole rest of the document), SBI is a composite, multi-cardholder statement where a second real
section legitimately begins after the first cardholder's closing boilerplate. The one-way,
permanent-suppression design this whole trigger family shares is correct for the first three banks
and actively harmful here — closing a 2-row near-miss by deleting an entire section (however broken
that section already was) is a strictly worse outcome, not a fix.

**Verdict: real, evidenced candidate; implementation attempted, verified unsafe, reverted. Not
shipped.** A real fix would need per-trigger resumption semantics (find the next real header/section
marker and resume normal processing there, rather than suppressing to end-of-document) — a genuinely
larger design change than adding a sixth marker to the existing list, not attempted in this pass.

## Summary

| Finding | Status |
|---|---|
| 20/21 ground-truthed documents: row-complete | Confirmed clean |
| Shivani_HDFC RD installment table (6 rows) | Correctly deferred — gated by ADR-005/M3, not a Phase 2 item |
| SBI CC Section 1 header-reconstruction collapse (~27-30 rows) | Confirmed still live. Not attempted — same class of problem C-8.3 already found hard |
| Statement.pdf (IOB) header-reconstruction collapse (~15 rows) | Not re-verified this pass, flagged for follow-up |
| HSBC DB.pdf header-reconstruction collapse | Not re-verified this pass, flagged for follow-up |
| SBI CC Section 0 trailing-boilerplate near-miss (2 rows) | Candidate marker found, implemented, found unsafe (deletes Section 1), reverted |
