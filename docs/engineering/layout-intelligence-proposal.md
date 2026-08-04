# Layout Intelligence & Observability

**Status:** Approved for Phase 0 (observability only). Mapping reuse is explicitly **not** in scope.
**Scope:** Import engine — reporting and intelligence, not parsing behaviour
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
starting point and a signal that nobody has yet *needed* it — which is why this phase reads the data
rather than adding another learning system on top of it.

## 2. Objective

> **Understand our documents.** Build the read side of the layout fingerprint data we are already
> collecting, so we can say which layouts are common, which are unreliable, which changed, and
> where the parser actually struggles.

The outcome of this phase is **knowledge, not optimisation**. If the data shows that layout reuse
would provide little or no value, that is a **successful** outcome — the decision gets made on
evidence instead of speculation, and this document closes with the measurement recorded.

### Explicitly out of scope

Not in this phase, and not to be added opportunistically:

- layout reuse
- mapping reuse
- skipping discovery
- cached mappings
- parser shortcuts

**No parsing behaviour changes in this phase.** The fingerprint is generated *after* structural
discovery (§3), so it cannot eliminate that work anyway. If reuse is ever revisited, it must be
backed by measurements rather than assumptions.

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

## 6. The questions, and whether the data can answer them

Every question below was checked against what is actually persisted. Three cannot be answered
today, and knowing that up front is the difference between a report that ships and one that stalls
halfway through.

### Layout intelligence

| Question | Answerable now? | From |
|---|---|---|
| How many unique layouts have we seen? | **Yes** | `statement_imports.layout_fingerprint` |
| Which layouts recur most frequently? | **Yes** | count by fingerprint |
| How stable are recurring layouts over time? | **Yes** | `activated_capabilities_json` + `layout_metadata_json` per import, ordered by date |
| Which layouts consistently parse successfully? | **Partly** | see *failed imports* below |
| Which layouts produce the most manual corrections? | **Yes** | `transactions.statement_import_id` → `statement_imports.layout_fingerprint`, filtered on the manual-categorisation flag |
| Which layouts consistently produce unknown headers? | **Yes** | `unknownHeaders` inside `layout_metadata_json` |
| Which layouts fail most often? | **No — data not captured** | see below |
| Do recurring layouts import faster than first-time ones? | **Yes** | `import_duration_ms`, first-seen vs subsequent |
| Which layouts changed after being stable? | **Yes** | capability/header set diff across imports sharing a fingerprint |

### Unknown header intelligence

| Question | Answerable now? | Note |
|---|---|---|
| Which unknown headers appear repeatedly? | **Yes** | aggregate `unknownHeaders` across imports |
| Which layouts always contain the same unknown headers? | **Yes** | group by fingerprint |
| Do unknown headers appear across multiple layouts? | **Yes** | the most useful one — an unknown header spanning several fingerprints is a hint-list gap, not a one-off |
| Which unknown headers get mapped manually? | **No — the feature does not exist** | see below |

### The three gaps

**1. Failed imports leave no trace.** `rejectIfNothingWasExtracted()` runs *before*
`createSession()`, so a document that fails to parse (`IMPORT_001` no table found, `IMPORT_007`
table found but every row rejected) throws before any row is written. There is **no fingerprint for
a failed import**, and no record it was ever attempted. "Which layouts fail most often" cannot be
answered from stored data at any point in the past.

Closing it means persisting a failure record at the point of rejection — a genuine pipeline change,
small but not free, and it only starts collecting from the day it ships. **Recommend treating this
as a separate decision**, not folding it silently into a reporting task.

**2. There is no manual column mapping in the product.** Users correct *categories*, not columns —
a repository-wide search finds no column- or header-mapping surface. "Which unknown headers
eventually get mapped manually" describes a user action that cannot currently happen. The question
is worth keeping as a design prompt, but it is not a report.

**3. Abandoned staging sessions are a partial substitute.** `import_sessions` carries its own
`layout_fingerprint` and a `status`, and a session that stages but is never confirmed keeps both. A
layout that is repeatedly staged and repeatedly abandoned is a real signal of a problem layout — the
closest available proxy for failure, and free to query.

## 7. Scope of this phase

Build the read side. Concretely:

1. Repository queries against `layout_fingerprint` on `statement_imports` and `import_sessions`.
2. An aggregation service answering the **Yes** rows above, alongside `CapabilityCoverageService`
   (see §9 on the counter overlap).
3. A way to see the output — admin-facing, following whatever the coverage metrics already do.
4. **Layout regression detection:** for a fingerprint with a history of successful imports, flag
   when a new import's capability set, unknown-header set, or manual-review rate diverges from the
   established pattern.

```
Layout FP-1-A1B2C3D4 · 10 successful imports
                    ↓
month 11 · same fingerprint · RUNNING_BALANCE no longer activates
                    ↓
"This layout changed" — surfaced, not silently absorbed
```

The goal is **not** to fix it automatically. The goal is to say that something changed. Because the
fingerprint deliberately excludes column order and x-positions, a same-fingerprint/different-
capabilities case is a genuine signal rather than noise.

## 8. Dependency check — confidence does not exist yet

The original note listed "existing confidence scoring framework" as a dependency. It is not
available. From the principles doc:

> "`Confidence` as a live metric still does not exist and is still gated (see Phase 3)…
> the coverage numbers produce **counts and nothing else** — no scoring, no thresholds, no
> auto-review decisions."

Nothing in §7 depends on it: counting, diffing and reporting need no scoring. This is recorded so
the phase is not accidentally re-scoped around a threshold that cannot be computed.

## 9. Open questions

- **Per-user or global?** Layout statistics aggregated across users are more useful and also a
  cross-tenant inference surface. Cross-user sharing was deferred to a privacy review, which implies
  per-user — but an admin-facing report is inherently cross-user. **Decide explicitly**: whether
  admin-visible aggregates are acceptable, and whether anything layout-derived may ever reach
  another user's import.
- **Counter overlap.** `CapabilityCoverageService` already aggregates per-document activation
  events. Layout reporting should extend it rather than start a parallel counter.
- **Signature granularity.** v1 excludes column order and position deliberately. Whether that is too
  generous (false matches) or too strict (missed recurrence) is itself answerable from stored rows,
  and is one of the more valuable outputs of this phase.

## 10. Success criteria

By the end of this phase we can answer: which layouts are most common, which are least reliable,
which changed over time, where the parser actually struggles, and what evidence exists that would
justify future layout learning.

**A negative result is a success.** If recurring layouts turn out to be rare, or recurring layouts
import no faster than first-time ones, that closes the mapping-reuse question on evidence — which
is the point.

## 11. Future work, explicitly not this phase

Revisit structural learning only once all three hold:

1. Layout intelligence exists (this phase),
2. A real confidence-scoring framework exists,
3. Evidence shows recurring layouts would benefit from reuse.

At that point layout history becomes one more source of evidence contributing to confidence — never
replacing validation, never bypassing the parsing pipeline.

---

**Final principle.** Finora should not learn banks. It should learn document structures. This phase
does not teach the engine anything new; it reads what the engine has already been recording, so the
decision about whether to teach it is made on evidence.
