# Layout Intelligence & Structural Learning

**Status:** Proposal — Phase 0 ready to build, Phases 1–2 gated
**Scope:** Import engine
**Relationship to other docs:** the capability model, the sequencing rules and the Capability
Backlog live in
[financial-document-intelligence-principles.md](financial-document-intelligence-principles.md);
what the engine has actually learned per cycle lives in
[financial-document-intelligence-changelog.md](financial-document-intelligence-changelog.md); how a
document travels through stage/review/confirm is
[import-flow.md](import-flow.md). This document covers only one question: what the engine should do
with the fact that it has seen a document's *structure* before.

---

## 1. What already exists

This is the part that most changes the shape of the work. The signature design is **not** a green
field — it is built, versioned, and has been collecting data.

| Piece | Where | State |
|---|---|---|
| Fingerprint generation | `DocumentContext.buildFingerprint()` | Built, versioned |
| Structural metadata | `DocumentContext.buildMetadata()` → `ImportDto.FinancialDocumentMetadata` | Built |
| Persistence | `import_sessions.layout_fingerprint`, `statement_imports.layout_fingerprint`, plus `layout_metadata_json` on both (migration V39) | Built |
| Capability activations | `DocumentContext.capabilities()`, aggregated by `CapabilityCoverageService` | Built |
| **Any read of the fingerprint** | — | **Does not exist** |

The v1 fingerprint spec, quoted from `DocumentContext`:

> SHA-256 of `{sourceFormat}|{headerCount}|{sorted normalized header set}`, first 8 hex characters,
> formatted `FP-1-XXXXXXXX`. Deliberately **excludes** header order, x-positions, page and table
> counts, parser name, and everything about the data rows.

The version prefix is load-bearing: it exists so a future spec change can re-derive old rows instead
of silently producing two incompatible keys for the same real layout.

**The column is write-only.** `buildFingerprint()` has exactly one caller
(`ImportSessionService`), and no repository queries it. Every import for months has computed and
stored a layout key that nothing has ever looked up. That is simultaneously the cheapest possible
starting point and a signal that nobody has yet *needed* it — which is why Phase 0 below is
measurement, not capability.

## 2. Objective

> Learn layouts to **improve confidence, detect regressions, and accumulate structural knowledge**,
> and to generate evidence about recurring document formats. Any future performance improvement is
> a bonus that must be demonstrated with measurements before the parsing pipeline changes.

Performance is deliberately **not** the justification. See §4.

## 3. What a fingerprint can and cannot do

A constraint worth stating plainly, because it rules out one common framing:

**The fingerprint cannot be used to skip discovery.** It is derived from the header set, and the
header set is the *output* of text extraction and table location. To compute the key you must
already have done the expensive work.

```
extract text ──► locate table ──► read headers ──► fingerprint
                                                    │
                                    (everything expensive is upstream)
```

That rules out "look up the layout, skip the parse". It does **not** rule out using a known
fingerprint to make the decisions that come *after* discovery better-informed:

| Goal | Viable with v1 fingerprint? |
|---|---|
| **A.** Skip discovery to save time | **No** — circular, see above |
| **B.** Disambiguate mapping decisions after discovery | Yes |
| **C.** Notice a familiar layout has changed | Yes |
| **D.** Report which layouts recur, and how stably | Yes, from data already stored |

Goals B–D are the subject of this proposal. Goal A would require a different signature derived from
pre-discovery signals — a v2 spec, out of scope, and not obviously worth it.

## 4. Why performance is not the justification

Column mapping today is static hint-array matching in `TransactionNormalizer` (`DATE_HINTS`,
`AMOUNT_HINTS`, `CREDIT_HINTS`, …) against a `Set`. It is microseconds. The costly stages —
PDFBox text extraction and table location — are upstream of the fingerprint and unaffected by any
of this.

This may not stay true. OCR, scanned statements, multi-table PDFs, AI-assisted extraction and
multilingual statements would all make structural memory materially more valuable. The position
here is not "layout memory is cheap therefore worthless" — it is **do not justify it on performance
until performance is measured**, because today the measurement would show approximately nothing.

`importDurationMs` is already recorded on every import, so this is measurable now.

## 5. Where structural memory genuinely improves correctness

Not every header is recognised. `FinancialDocumentMetadata.unknownHeaders` is already computed and
stored as *"the subset of headers not matched by any recognized hint list"* — the "never lose
information" principle extended from rows to columns.

That set is exactly where layout memory earns its keep:

- A header **in** the hint lists needs no learning. `Withdrawal`/`Deposit` already map correctly —
  they were added to `AMOUNT_HINTS`/`CREDIT_HINTS` after a real Kotak statement staged every credit
  as an expense. Static hints already solved it.
- A header **not** in the hint lists is currently a permanent unknown. It lands in
  `unknownHeaders` on every import of that layout, forever, and the engine re-learns nothing.

So the correctness question is narrower and better-posed than "remember mappings": *when the same
fingerprint recurs and the same unknown header keeps appearing, what did the engine end up doing
with that column, and was it right?* That is accumulated knowledge, and the raw material is already
in the database.

## 6. Dependency check — confidence does not exist yet

The original note listed "existing confidence scoring framework" as a dependency. It is not
available. From the principles doc:

> "`Confidence` as a live metric still does not exist and is still gated (see Phase 3)…
> the coverage numbers produce **counts and nothing else** — no scoring, no thresholds, no
> auto-review decisions."

This matters for sequencing. Anything phrased as "raise confidence by 15%" or "reuse automatically
above threshold" has an unmet dependency and cannot be built first. Regression detection and
evidence reporting do **not** depend on confidence, and can.

## 7. Phases

### Phase 0 — Report on the data already collected *(no new pipeline behaviour)*

Make the write-only column readable and answer, from existing rows:

- How many distinct layouts exist, per user and overall?
- How many are one-offs versus genuinely recurring?
- For a recurring fingerprint, is the capability set stable across imports?
- Does `unknownHeaders` stay the same for a given fingerprint?
- Do recurring layouts differ from first-time layouts in `importDurationMs`?

That last one is the measurement that decides whether Goal A is ever worth revisiting:

```
first-time avg   420 ms
recurring avg    415 ms   → no optimization to win; stop here
recurring avg    160 ms   → now there is evidence
```

**Nothing in the parsing path changes in Phase 0.** No `LearnedLayout` entity, no reuse, no
thresholds. This phase exists to find out whether the later phases are worth building, and it is the
only phase justified by evidence that exists today.

### Phase 1 — Regression detection *(gated on Phase 0 showing recurrence)*

For a fingerprint seen successfully before, compare this import's capability set and unknown-header
set against the established pattern, and surface a *drift* signal when they diverge:

```
Layout FP-1-A1B2C3D4 · 10 successful imports
                    ↓
month 11 · same fingerprint · RUNNING_BALANCE no longer activates
                    ↓
"This statement's layout changed" — surfaced, not silently absorbed
```

The value here is explainability, not speed. It turns "the numbers look odd this month" into a
specific, attributable structural change. Note the fingerprint deliberately excludes column order
and positions, so a same-fingerprint/different-capabilities case is a genuine signal rather than
noise.

### Phase 2 — Structural evidence contributes to interpretation *(gated on Phase 1 and on confidence existing)*

Only once confidence is a real metric: let a recurring layout's prior successful interpretation act
as **one input among several** when the current document is ambiguous. Advisory, never
authoritative; validation is unchanged and unconditional.

## 8. Non-negotiables

1. **Learn structures, never institutions.** No bank identity in the signature — two banks with the
   same structure share a layout; one bank with a new structure gets a new one.
2. **Advisory, never authoritative.** Structural memory contributes evidence. It never bypasses
   validation or short-circuits a check.
3. **Learn only from confirmed, validated imports.** Never from failed or partial ones.
4. **Forget cleanly.** Divergence triggers rediscovery, not correction-in-place.
5. **Never store customer data.** Structure only — no rows, no account numbers, no names.

## 9. Open questions

- **Per-user or global?** §12 of the original note specifies no `userId`. If a layout learned from
  one user's statement can influence another user's import, that is a cross-tenant inference
  channel and needs an explicit decision, not an omission. Cross-user sharing was listed as future
  work "subject to privacy review", which implies per-user for v1 — but the entity as drafted is
  global. **Resolve before Phase 1.**
- **Counter overlap.** The proposed `validationSuccessCount`/`validationFailureCount` may duplicate
  what `CapabilityCoverageService` already aggregates from activation events. One counter or two.
- **Signature granularity.** v1 excludes column order and position on purpose. Whether that is too
  generous (false matches) or too strict (missed reuse) is a Phase 0 question, answerable from
  stored rows.

## 10. Success criteria

Phase 0 succeeds if it produces a defensible **yes or no** on whether recurring layouts are common
enough to justify Phase 1 — including the outcome where the answer is no and this document is
closed with the measurement recorded.

Phases 1–2 succeed if layout drift is surfaced with a specific structural cause, reused structural
evidence never overrides validation, layout changes trigger rediscovery automatically, and no
institution-specific logic enters the engine.

---

**Final principle.** Finora should not learn banks. It should learn document structures. Category
learning taught the engine what transactions *mean*; layout learning should teach it how documents
are *organised* — as accumulated, explainable evidence, not as a cache.
