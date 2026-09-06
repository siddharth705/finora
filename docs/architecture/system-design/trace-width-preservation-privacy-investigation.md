# Trace width preservation — privacy, necessity, safety, corpus integrity

**Status:** investigation only. Read-only. Nothing edited, nothing committed. No production code,
no test code, no fixture tooling changed.

**Scope:** the four questions the PM asked about `PdfTraceRedactor` discarding run width, and about
`TraceMetadata.hasNoWidths()`. Does not touch Track A, C-8/C-8.3, OCR routing, R2, the evidence
engine, or any other backlog item. Raw customer PDFs were not opened; everything below comes from
repo source, committed traces, and the two redacted v3 candidate traces already on disk.

---

## Finding

Four findings, in the order the questions were asked.

**F1 — The privacy question is much smaller than it looks, and for the tokens that actually matter
it is nearly empty.** Width leaks nothing at all for masked *digit* runs (account numbers,
references, phone numbers) because digits are uniform-width in every standard PDF text font. For
masked *alphabetic* runs it leaks the letter-multiset width sum, worth roughly 5–6 bits: measured
against a 24k-word length-7 corpus it narrows the candidate set ~40x and uniquely identifies 0.04%
of words. Real but marginal, and it only ever applies to a token whose length the trace already
publishes.

**F2 — Width preservation is not needed for masked runs at all.** `RIGHT_ALIGNED_AMOUNTS` is
guarded on `CsvParser.parseNumeric(t.text().trim()) != null`, i.e. it only ever acts on runs whose
whole text is a number — and the redactor preserves those *verbatim*. Its second input,
`headerEnds`, comes from header-label runs, which are structural allowlist words and are also
preserved verbatim. **Every input `RIGHT_ALIGNED_AMOUNTS` needs is already unmasked content.** The
same is true of the only other width consumer in production, `StatementSummaryExtractor#valueUnder`
(structural label vs. numeric value). So a fix that preserves width *only for runs redaction left
byte-identical* fully unblocks the capability and has, by construction, zero incremental privacy
cost.

**F3 — Therefore candidate (a) is strictly dominant.** It is sufficient for the merged-cell case,
sufficient for `PRINTED_SUMMARY_TOTALS`, and leaks nothing that the preserved text does not already
state. Candidates (b) and (c) buy no additional validation capability for the known cases, so their
privacy cost — however small — buys nothing.

**F4 — `TraceMetadata.hasNoWidths()` is wrong today, independently of any of the above, and the two
v3 candidate traces on disk already demonstrate the failure.** It reports "this trace has widths"
purely because the magic line says v3, while all 1408 and all 137 rows respectively carry width
`0.00`. It is a format-version check masquerading as a data check.

---

## Evidence

### The defect itself

`backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceRedactor.java:133-140`:

```java
public static List<PositionedText> redact(List<PositionedText> runs) {
    Set<String> vocabulary = vocabulary();
    List<PositionedText> out = new ArrayList<>(runs.size());
    for (PositionedText run : runs) {
        out.add(new PositionedText(redactText(run.text(), vocabulary), run.x(), run.y(), run.pageIndex()));
    }
    return out;
}
```

Line 137 calls the **4-arg** constructor. `PositionedText`
(`backend/src/main/java/com/finora/imports/pdf/PositionedText.java:55-57`) defines that overload as
`this(text, x, y, pageIndex, 0f)`. So every redacted run's width is 0 by construction, regardless of
trace format version. `run.width()` — the real measurement — is in scope on line 137 and simply not
passed.

Width IS measured correctly upstream, at
`backend/src/main/java/com/finora/imports/pdf/PdfTextExtractor.java:69-76`:

```java
TextPosition last = textPositions.get(textPositions.size() - 1);
float width = Math.max(0f, (last.getXDirAdj() + last.getWidthDirAdj()) - first.getXDirAdj());
```

i.e. the run's right edge from the last glyph's advance (kerning included), minus the first glyph's
x. It is a *rendered geometry* measurement, not a character count. No font identity, font size, or
per-glyph data is recorded anywhere — only this single scalar.

The capture path is `PdfPipelineDiagnostic#captureRedactedTrace`
(`backend/src/test/java/com/finora/imports/pdf/PdfPipelineDiagnostic.java:114-152`): extract →
`PdfTraceRedactor.redact` → `PdfTrace.format` with `CURRENT_TRACE_VERSION = 3` → validate → write.
The version stamp at line 124 is unconditional, so a capture *always* claims v3 even though line 137
guarantees the widths are all zero. The two are stamped independently, which is exactly how the
current inconsistency arises.

### The zero-width v3 traces are already on disk

`backend/src/test/resources/traces/` currently holds five files. Two are untracked v3 recapture
candidates:

| file | rows | rows with non-zero width |
|---|---|---|
| `hdfc-composite-deposit-schedules-v3-candidate-1.trace` | 1408 | **0** |
| `hdfc-txn-date-narration-header-v3-candidate-1.trace` | 137 | **0** |

Both declare `# finora-pdf-trace v3`, `traceVersion: 3`, `redactorVersion: 2`,
`allowlistFingerprint: A3C0126B`, `capturedAt: 2026-08-11`. Both therefore report
`hasNoWidths() == false` while carrying no widths at all.

Note also `PdfTrace.committedTraceNames()`
(`backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTrace.java:169-178`) enumerates the
directory rather than a list, so these candidate files are *already* in scope for
`TraceCorpusHealthTest` on any local run — the "looks repaired when it isn't" effect is live on
disk today, not hypothetical at commit time.

### What `RIGHT_ALIGNED_AMOUNTS` actually consumes

`backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java:1568-1574`:

```java
if (t.width() > 0 && headerEnds != null && CsvParser.parseNumeric(t.text().trim()) != null) {
    int byRightEdge = nearestColumn(t.endX(), headerEnds);
    if (byRightEdge != nearest && isAmountColumn(headerNames.get(byRightEdge))) {
        nearest = byRightEdge;
        if (ctx != null) ctx.record("RIGHT_ALIGNED_AMOUNTS");
    }
}
```

Two width inputs, and only two:

1. **`t.width()` on the run being bucketed**, and the run must satisfy
   `CsvParser.parseNumeric(t.text().trim()) != null` — a pure number. In the redactor, a bare number
   matches `AMOUNT_LIKE` (`PdfTraceRedactor.java:83-84`) and is returned unchanged
   (`:213`, `if (DATE_LIKE... || AMOUNT_LIKE.matcher(bare).matches()) return token;`). The
   `LONG_DIGIT_RUN` pre-check at `:211` masks 8+-digit runs first, but those are also exactly the
   runs `parseNumeric` would not be reading as a ledger amount in an amount column, and a masked
   token becomes `999…` which still fails to be the original value anyway. **Conclusion: every run
   `RIGHT_ALIGNED_AMOUNTS` can act on is an unmasked, verbatim-preserved amount.**

2. **`headerEnds`**, built at `PdfTableLocator.java:538-543` from `t.endX()` of each header-row run,
   i.e. header label widths. In the candidate trace the relevant header runs are single unmasked
   runs — `Narration` (x=175.83), `Withdrawals` (295.83), `Deposits` (385.92), `Closing Balance`
   (472.98) — all present verbatim, all currently width `0.00`. Multi-line header cells go through
   `asOneCell` (`:1386-1401`), which deliberately refuses to synthesise a width unless
   `anyMeasured` — so header width must be real for the correction to be reachable.

Nothing in this path reads the width of a masked run. Grepping every width consumer in production
confirms the surface is tiny:

```
PdfTableLocator.java:542   headerEnds.add(t.endX());          // header labels — unmasked
PdfTableLocator.java:1390/1397/1412  asOneCell/endOf          // header labels — unmasked
PdfTableLocator.java:1568-1569  RIGHT_ALIGNED_AMOUNTS         // numbers + header labels — unmasked
StatementSummaryExtractor.java:161  valueUnder overlap        // structural label vs amount — unmasked
BoundingBox.java:38        evidence-engine overlap            // not fed from traces
```

`StatementSummaryExtractor#valueUnder` (`:157-167`) matches a summary *label* (structural
vocabulary) against a *value* (amount or count) by horizontal span overlap — again both sides
unmasked. That is `PRINTED_SUMMARY_TOTALS`, the *other* capability listed with "no trace" in
`CapabilityCorpusCoverageTest.DECLARED_WITHOUT_A_TRACE` (`:116`), so the unmasked-only fix likely
unblocks both shortfall entries, not one.

### The redactor can tell masked from unmasked at exactly the right point

At `PdfTraceRedactor.java:137` both the original text (`run.text()`) and its redacted form are in
hand in the same expression. A run is *fully unmasked* iff the two strings are equal — redaction is
purely character-substituting (`mask`, `:222-230`, preserves length and every non-alphanumeric
char), so string equality is an exact, total test for "nothing about this run was hidden". No new
classification logic, no new pattern, no second pass over the vocabulary is needed to implement
candidate (a); the discriminator is already sitting on that line.

For the record, on `hdfc-composite-deposit-schedules-v3-candidate-1.trace`: 1408 runs total, 549
contain a mask marker, 343 are pure amount-shaped runs.

### `hasNoWidths()` today

`backend/src/test/java/com/finora/imports/pdf/fixtures/TraceMetadata.java:92`:

```java
public boolean hasNoWidths() { return traceVersion < 3; }
```

Its documented contract, one line above, is "True when the rows carry no measured width, so any
capability guarded on `width() > 0` is unreachable on this trace." The implementation does not test
that; it tests the format version, which is only a proxy — and a proxy the two candidate files
already falsify. Its single consumer is `TraceValidator.java:121-129`, which raises a REVIEW finding
telling the reader to "Recapture at trace v3 to fix" — advice that is now actively wrong, since a v3
recapture is precisely what produced the zero-width files.

---

## Privacy risk

Analysis restricted to genuinely MASKED tokens, as instructed. For unmasked tokens (amounts, dates,
structural words, bank names, IFSC bank prefixes) the content is already in the file in cleartext,
so publishing its rendered width is not a privacy question at all — the width is a deterministic
function of text the reader already has, given the font.

**What the trace already publishes.** Redaction preserves length and character class exactly
(`PdfTraceRedactor.java:26-27` doc, `mask` at `:222-230`) plus x, y and page. So an attacker already
knows: how many characters, which were letters vs digits, upper vs lower case, every punctuation
character verbatim, and the exact page position. Width is an *additional* real-valued observation on
top of that.

**Digits: zero incremental leak.** In Helvetica — and in virtually every text font used for
financial documents, by deliberate design so that columns of figures align — all ten digit glyphs
share one advance width (556/1000 em in Helvetica). A masked account number `999999999999` therefore
has a width that is a pure function of its already-published length. Account numbers, card numbers,
customer IDs, phone numbers, reference numbers — the highest-sensitivity masked tokens in a bank
statement, and the ones `LONG_DIGIT_RUN` (`:86-87`) exists to catch — leak **nothing whatsoever**
through width. This is the single most important fact in the privacy analysis and it removes the
worst-case category entirely.

**Letters: ~5–6 bits, no unique identification.** For proportional text, width is the sum of the
glyph advances, so it constrains the *multiset* of letters, not their order. I measured this rather
than estimating it: taking Helvetica's uppercase advances at 8pt (the font/size the prior
investigation validated to two decimals against the real HDFC withdrawal edges — see
`hdfc-merged-cell-extraction-investigation.md`, "Confirming experiment"), and using the 236k-word
system dictionary as a stand-in for a name corpus:

| token length | candidate words | distinct widths | expected surviving candidates | uniquely identified |
|---|---|---|---|---|
| 5 | 10,239 | 66 | 360 | 2 (0.02%) |
| 7 | 23,881 | 117 | 598 | 10 (0.04%) |
| 9 | 32,412 | 154 | 663 | 11 (0.03%) |
| 12 | 20,468 | 193 | 318 | 15 (0.07%) |

Reading the length-7 row: knowing "7 letters" leaves ~24k candidates; knowing "7 letters rendered at
47.31pt" leaves ~598. That is a ~40x narrowing, about 5.3 bits. It is a real reduction and I will
not call it nothing — but it does not identify a name, and the residual set is in the hundreds. A
dictionary is a rough proxy for Indian surnames (different letter-frequency profile, different
length distribution), so treat these as order-of-magnitude, not exact. I could not measure against a
real name corpus without adding one to this machine, and did not.

Two caveats that cut in opposite directions:

- *Worse than the table suggests:* PDFBox runs are line-ish, not word-ish — the candidate trace has
  `Xxxxxxx Xxxxxx Xxxxxx` (a full three-part name) as one run. A single width over a 21-character
  multi-token string carries more absolute entropy than one over 7 characters. But the candidate
  space grows far faster than the constraint does (a three-name combination space is the product of
  three name spaces), so the *fractional* narrowing stays in the same 40x-ish region.
- *Better than the table suggests:* the attacker must know the font and size to invert anything. The
  trace records neither. They are inferable for a known bank template (this investigation's
  predecessor inferred them successfully), so I assume the attacker has them — but it is an extra
  step, and it is per-document.
- *Uncomfortable but minor:* under a naive "just fix the constructor" change the recorded width
  would describe the *original* glyphs while the recorded text shows `Xxxxxxx`. The artefact would
  be internally inconsistent — a reader could see that width and text disagree. That is a code-smell
  more than a leak, but it is a reason to prefer not doing it.

**Severity verdict: marginal, and concentrated entirely in masked alphabetic tokens (names,
addresses, narration counterparties). Negligible-to-nil for masked numeric tokens.** It is not a
re-identification risk on its own; it is a modest search-space reduction on top of a length leak the
format already accepts by design. It is, however, an *unnecessary* marginal risk, because
(see below) nothing in the codebase needs it.

---

## Alternatives

### (a) Preserve real width only for runs redaction left byte-identical

**Privacy risk: none.** The width is a deterministic function of text already published verbatim in
the same file. No new information is added under any threat model.

**Sufficient for the merged-cell case: yes.** Both inputs to `PdfTableLocator.java:1568-1569` are
unmasked runs — the amount `0.00` at x=342.32 (preserved by `AMOUNT_LIKE`) and the header labels
`Withdrawals`/`Deposits`/`Closing Balance` (preserved by `STRUCTURAL_WORDS`). Also sufficient for
`StatementSummaryExtractor#valueUnder`, i.e. plausibly for `PRINTED_SUMMARY_TOTALS` as well.

**Cost:** masked runs keep width 0. A future capability that needed the geometry of a *masked* run —
e.g. a narration-wrap or bounding-box heuristic in the evidence engine — would find that run
width-blind. Nothing in `main/` reads a masked run's width today; this is a forward-compatibility
cost, not a present one, and it would be visible (width 0) rather than silent.

### (b) Quantised width for masked runs (e.g. round to 10pt)

**Privacy risk: very low but non-zero,** and *unquantified precision loss on the thing that matters*.
The whole point of the measurement (`PdfTextExtractor.java:69-72`) is that it be "exact enough to
separate two adjacent right-aligned amount columns", and the real margin in the HDFC case is 1.44pt.
A 10pt bucket destroys that. If quantisation were applied to *masked* runs only it would not damage
`RIGHT_ALIGNED_AMOUNTS` (which never reads them) — but then it is candidate (a) plus a low-value
extra field that no code consumes. **Buys nothing over (a).**

### (c) Exact real width for masked runs too ("just fix the constructor")

**Privacy risk: marginal — the 5-6-bit letter-multiset leak quantified above,** plus the
text/width inconsistency noted. **Sufficient: yes, but it is sufficient only via the same unmasked
runs (a) already covers.** It accepts a privacy cost for zero incremental validation capability.
This is the option to reject, and the reason to reject it is necessity, not fear.

### (d) One-time uncommitted diagnostic against the original PDF

Run the extractor on the source document and assert `RIGHT_ALIGNED_AMOUNTS` fires and the row
resolves, without writing widths into any artefact. This is *evidence* — it would promote the prior
investigation's INFERRED verdict to MEASURED for those two documents on that day.

It is **not a substitute for a committed trace.** The corpus's stated purpose
(`TraceMetadata`'s class doc, "A trace is evidence, not test data";
`TraceWidthFidelityTest`'s closing note that "the end-to-end proof arrives with the first v3
capture… and it arrives automatically") is *ongoing regression protection*: a future refactor of
`bucketRow` must turn a test red. A one-off verdict on a document the build machine will never see
again cannot do that, and the machine holding the PDF is not the build machine
(`TraceCorpusHealthTest:64-73` says so explicitly). Useful as a *complement* — it would also
independently confirm production is correct today — but the durable answer still needs width in the
committed file. Which (a) provides, at no privacy cost.

### (e) Corpus-integrity fix, orthogonal to all of the above

Change `hasNoWidths()` to test the parsed rows rather than the version stamp. Independent of (a)–(d)
— see below.

---

## Recommended approach

**1. Approve candidate (a): preserve real width only for runs redaction left byte-identical.**

Reasoning: it is the only candidate that is simultaneously *sufficient* (it supplies both width
inputs `RIGHT_ALIGNED_AMOUNTS` reads, and both inputs `StatementSummaryExtractor` reads) and
*costless* (the preserved width describes text the file already publishes in cleartext). The
privacy question the PM was worried about turns out not to be a tradeoff at all — it dissolves,
because the capability never needed masked-token geometry in the first place. The discriminator is
already available at `PdfTraceRedactor.java:137` as a string equality, so this is not a more
elaborate change than (c); it is a differently-scoped one of similar size.

Approving (a) rather than (c) also means no future reviewer has to re-litigate the privacy question
each time the corpus grows, and it keeps the artefact internally consistent: in a trace produced
under (a), width is always the true width of the text printed next to it.

**2. Approve the `hasNoWidths()` fix immediately and separately — it does not depend on 1.**

The correct condition, precisely: `hasNoWidths()` cannot be answered from metadata alone, because
metadata does not see the rows. The check belongs where the rows are already parsed — in
`TraceValidator.validate`, at the point it holds `runs` (`TraceValidator.java:~121`), evaluated as
"no run in this trace has `width() > 0`" (equivalently `runs.stream().noneMatch(r -> r.width() > 0)`).
Keep the version stamp as a *separate* signal if desired, but the width finding must key off the
data. Two distinct diagnoses then become expressible, and they need different remedies:

- v1/v2 trace → the *format* carries no width column → recapture at v3 (the current message).
- v3 trace with all-zero widths → the *redactor* dropped them → recapture will not help until the
  redactor is fixed; the current "Recapture at trace v3 to fix" advice is actively misleading here.

A third state — v3 with *some* zero widths — is expected and benign under candidate (a) (masked runs
legitimately carry 0), so the condition must be "**all** widths are zero", not "any width is zero".
That is worth deciding before the (a) work lands, since a naive "any zero width" check would fail
every trace produced under (a).

**Recommended sequencing:** fix `hasNoWidths()` first, on its own. It is a two-line test-infra change
with no privacy dimension, and it is what stops a zero-width v3 trace being committed under the
impression that the corpus is repaired. Then land (a), then recapture, then let
`CapabilityCorpusCoverageTest`'s ratchet remove `RIGHT_ALIGNED_AMOUNTS` (and possibly
`PRINTED_SUMMARY_TOTALS`) from `DECLARED_WITHOUT_A_TRACE` on its own.

**Do not commit the two `-v3-candidate-1` traces as they stand.** They are already visible to
`PdfTrace.committedTraceNames()` from the working directory, and they currently report
`hasNoWidths() == false` with 0/1408 and 0/137 real widths.

---

## Regression impact

- **`hasNoWidths()` fix in isolation:** changes only a REVIEW-severity finding's text and firing
  condition. `TraceValidator.Result.isCommittable()` is driven by BLOCKERs, so no capture is newly
  refused and no existing trace becomes uncommittable. `TraceValidatorTest` has cases around legacy
  traces (`:123` region) that may assert on the finding; expect to update those. The three existing
  v1 traces keep reporting `hasNoWidths() == true` under either implementation, since all their
  widths are 0 — so the corpus's current state does not move.
- **Candidate (a):** production code is untouched; only the redactor's constructor call site
  changes. Existing v1 traces parse identically (`PdfTrace.parse`, `:113-135`, four-field rows still
  yield width 0). `TraceWidthFidelityTest`'s four assertions are about the *format*, not the
  redactor, and none of them should move.
- **After recapture with (a):** replaying the HDFC traces should newly activate
  `RIGHT_ALIGNED_AMOUNTS` and resolve the 13 + 1 merged ledger cells. That will change extraction
  output for those documents, so **`GoldenOutputSnapshotTest` baselines will move** — and that is
  the intended outcome, since the current baselines are frozen against the defective merge
  (`hdfc-txn-date-narration-header.golden.txt:5` records a row missing its `Withdrawals` column).
  Any golden-file update here should be reviewed as a *correction*, and the diff read line by line,
  not regenerated blind.
- **`CapabilityCorpusCoverageTest`:** its ratchet turns red the moment a trace exercises a
  capability listed in `DECLARED_WITHOUT_A_TRACE` (`:106-121`). Expect to remove the
  `RIGHT_ALIGNED_AMOUNTS` entry, and check `PRINTED_SUMMARY_TOTALS` at the same time.
- **Not verified here:** I did not run the suite, did not run a capture, and did not open the source
  PDFs. Everything above is static reading of code and of the two already-redacted candidate traces.
  The claim that candidate (a) *actually* makes `RIGHT_ALIGNED_AMOUNTS` fire on the real documents
  remains INFERRED — high confidence, from the guard condition and the redactor's preservation
  rules — until a recapture demonstrates it.
