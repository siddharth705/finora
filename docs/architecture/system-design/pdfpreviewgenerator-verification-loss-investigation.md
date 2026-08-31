# VerificationReport loss on the single-account PDF path

Status: investigation only. No code changed. Untracked, uncommitted.
Date: 2026-08-11. Scope: `PdfPreviewGenerator.generate()` vs `generateSections()`, and the
`StagedAccountSection -> StagingResponse` conversion. Nothing about OCR routing, Track A, C-8, or
`com.finora.imports.evidence`.

## 1. The mechanism

Verification is computed on **one** path, for **every** section, and is then dropped by **two**
separate `StagedAccountSection -> StagingResponse` conversions.

Where it is computed:

- `PdfPreviewGenerator.buildLedgerSection` calls `importVerifier.verify(...)` at
  `backend/src/main/java/com/finora/imports/pdf/PdfPreviewGenerator.java:400-403` and returns it via
  the six-arg `StagedAccountSection` constructor at `:404`.
- `generate()` is a thin wrapper over the same path, so it *does* have the report in hand:
  `PdfPreviewGenerator.java:127` gets `sections().get(0)` (a fully-populated `StagedAccountSection`,
  verification included).

Where it is lost:

- **Loss point A — `PdfPreviewGenerator.java:128`.**
  ```java
  return new StagingResponse(first.rows(), first.totalParsed(), first.flaggedDuplicates(),
                             first.detectedAccount(), first.unparseableRows());
  ```
  This is the **five**-arg convenience overload, which explicitly defaults verification to `null`
  (`backend/src/main/java/com/finora/dto/ImportDto.java:291-294`). `first.verification()` is never
  read. The original suspicion is confirmed exactly as stated.

- **Loss point B — `backend/src/main/java/com/finora/imports/ImportService.java:387-389**, method
  `toStagingResponse(StagedAccountSection)`. Identical five-arg call, identical omission. This one
  matters more than A (see §2). Git history shows why it was missed: the method was written at
  `0ac245b`, extended for `unparseableRows` at `3274f3a`, and the verification field was added to
  the DTO later without this call site being revisited.

`StagingResponse` is fully capable of carrying the report — the six-arg canonical constructor at
`ImportDto.java:285-287` has the field, and the CSV path populates it
(`backend/src/main/java/com/finora/imports/PreviewGenerator.java:161-165`). So this is a
conversion-site omission, not a missing DTO capability.

## 2. Callers, and which are production

`generate()` (production callers): exactly one —
`ImportService.parseAndStageAnyFormat`, `ImportService.java:451`, taken only when
`sourceFormat == "PDF"` **and** `sourceSectionIndex == null` (i.e. a single-account PDF).

`toStagingResponse` (production callers): two —
- `ImportService.java:304`, the **single-section branch of the live PDF upload endpoint**
  (`POST /import/pdf/stage` -> `ImportController.java:68-74` -> `parseAndStagePdfWithSession`);
- `ImportService.java:449`, the section-indexed re-import branch of `parseAndStageAnyFormat`.

`generateSections` / `generateSectionsWithContext` (production): `ImportService.java:289` (the
whole-document parse), `ImportService.java:444`, `AdminAnalysisService.java:109`.

Who reaches `parseAndStageAnyFormat` in production: `StatementImportService.reimport`
(`StatementImportService.java:194`) and `StatementImportService.confirmReimport`
(`:250`) — the "Re-import Statement" action on the Statement History page.

Net effect by scenario:

| Scenario | Verification in response? | Why |
|---|---|---|
| CSV upload | **Yes** | `PreviewGenerator.java:165` uses the six-arg ctor |
| PDF upload, **2+ sections** detected | **Yes** | returns `sections` directly, no conversion (`ImportService.java:317`) |
| PDF upload, **1 section** (the common case) | **No** | loss point B via `ImportService.java:304` |
| PDF re-import, single-account | **No** | loss point A via `ImportService.java:451` |
| PDF re-import, section-indexed | **No** | loss point B via `ImportService.java:449` |

So the multi-account composite statement — the *rarer* document — is the only PDF that keeps its
verification, and the ordinary one-account bank statement is the one that loses it.

## 3. What the user experiences

The frontend consumes the field and renders it, so the data would be visible if present:

- `frontend/src/pages/Import.tsx:268` — `setVerification(staging.verification ?? null)` for the
  single-account response; rendered at `:824` via `<VerificationPanel verification={verification} />`.
- `Import.tsx:215` — same for the re-import response (`reimportState.staging.verification`).
- `Import.tsx:97` / `:739` — the multi-section path, which does receive a report.
- `frontend/src/components/VerificationPanel.tsx:27` — `if (!verification) return null;`

That last line is the decisive one: **absent verification renders nothing at all.** There is no
"verification unavailable" state. A single-account PDF whose balance chain failed and one that
verified cleanly produce a byte-identical review screen. The user cannot distinguish "checked and
clean" from "never checked".

Nothing else catches it. `rejectIfNothingWasExtracted` (`ImportService.java:385`) only rejects a
*zero-row* extraction; a statement with a real balance-chain break or a totals mismatch stages a
full set of plausible rows and passes that check. Confirm builds transactions from the submitted
rows and re-runs no verification.

Secondary loss — telemetry. `ImportService.java:308-310` passes
`singletonList(staged.verification())`, i.e. `singletonList(null)`, to `recordPdfParsed` ->
`ImportVerificationRecorder.recordForAnalysis`. `ImportVerificationRecorder.java:282` skips null
reports silently, so **zero rows** are written to `import_verification_findings` for every
single-account PDF import. Multi-section PDFs (`ImportService.java:316-319`, which maps
`StagedAccountSection::verification`) and CSV imports do record. Any analysis of verification
outcomes across the corpus is therefore blind to the most common PDF shape.

## 4. Live or inert?

**Live.** `POST /import/pdf/stage` is a real, wired endpoint (`ImportController.java:68`), and the
`sections.size() <= 1` branch is the documented common case (see the comment at
`ImportService.java:300-301`). Loss point B fires on essentially every ordinary single-account PDF
upload. Loss point A (`generate()`) is narrower — only PDF re-imports of single-account statements —
but is also reachable, not dead.

Not determinable from this codebase: how often real users hit a *failing* verification on a
single-account PDF (that needs production data, and §3 shows this path records nothing, so the
telemetry that would answer it does not exist for exactly this population).

Why no test caught it: every existing assertion on verification is made at the
`StagedAccountSection` level (`PdfPreviewGeneratorTest.java:143,148`,
`SummaryAttributionPdfPreviewGeneratorTest.java:63,129`,
`UnreadableStatementKeepsItsEvidenceTest.java:59`) — i.e. upstream of both conversions. No test
asserts on `StagingResponse.verification()` for a PDF.

## 5. Recommendation (not implemented)

**Fix soon.** This is the same failure class the marker-row work (`ade05ca`) was about: the pipeline
computes the evidence that a read went wrong, then discards it, leaving the user with a financially
plausible but unchecked import and no signal that anything was skipped. It is worse than the
originally-reported scope, because the dominant production path (loss point B, single-account PDF
upload) is affected, not just the re-import wrapper.

Narrowest correct fix, at design level — two one-line conversion changes, no restructuring:

1. `ImportService.toStagingResponse` (`ImportService.java:387-389`): pass
   `section.verification()` as the sixth argument.
2. `PdfPreviewGenerator.generate` (`PdfPreviewGenerator.java:128`): same, pass
   `first.verification()`.

Reduce (2) to (1) if preferred by having `generate()` build its response through the same conversion
helper, but the helper currently lives in `ImportService` and moving it is a wider diff than the fix
warrants. Do **not** make `generate()` delegate differently or change section selection — the
selection logic is correct; only the field copy is missing.

Worth considering alongside, as separate PM decisions (out of scope here):
- Remove the five-arg convenience overloads on `StagingResponse`/`StagedAccountSection`
  (`ImportDto.java:291`, `:309`) once the remaining call sites are audited, so "silently null
  verification" stops being the easy default. Note `PdfPreviewGenerator.java:471-477` documents a
  previous instance of exactly this overload causing exactly this bug.
- Add a regression assertion at the `StagingResponse` level for a single-account PDF, since the
  current suite provably cannot see this class of loss.
