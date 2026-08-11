# Trace Lifecycle

**What this governs:** the `.trace` fixtures under `backend/src/test/resources/traces/` — how one
is created, redacted, validated, regenerated and retired, and how each maps back to a real
capability.

**Why it exists as a document:** the trace corpus is production-grade evidence held to test-fixture
discipline, and the gap between those two showed up twice in one week — a customer's account number
reaching the repository, and three traces silently losing the vocabulary they were captured to
preserve. Both were failures of process, not of anyone's attention.

---

## The principle everything here follows

> **A trace is evidence, not test data.**
>
> It exists to preserve the real-world document structure that motivated a capability — the exact
> fragmentation, the exact coordinates, the exact column headers a real bank's PDF generator
> produced. If a trace no longer contains that evidence, the capability loses its grounding: the
> test still runs, still passes, and no longer demonstrates anything about the document it came
> from.

This is the trace-corpus form of "Evidence before capability" from
[`financial-document-intelligence-principles.md`](../../architecture/system-design/financial-document-intelligence-principles.md).
A capability is justified by a real document; a trace is how that document keeps justifying it after
the document itself is deleted.

---

## 1. Creation

One command:

```bash
./scripts/trace-capture.sh <trace-name> <path-to.pdf> \
  --source "<what the document is>" \
  --capabilities "CAPABILITY_A,CAPABILITY_B" \
  --requires "Maturity Date,Rate of Interest" \
  --regressions "#212,#247" \
  --why "<one sentence: what this document taught the engine>"
```

**Naming.** Capability-descriptive, never a bank name on its own — the same rule that governs
parser and test names. A trace name should survive the day a second bank ships the same layout.

**The source document never enters the repository.** The script refuses a path inside the repo, even
untracked: an untracked customer statement is one `git add -A` from being committed.

**`--requires` is not optional in spirit.** It is the list of structural tokens that must survive
redaction for the trace to still be evidence. Capturing without it is allowed and warned about,
because a trace that asserts nothing about its own contents can be hollowed out later with every
test still green.

## 2. Redaction

`PdfTraceRedactor` masks by **allowlist, not denylist** — a token is kept only if it is recognisably
statement furniture (a column header, a metadata label, a date, an amount, a bank name). Everything
else is replaced character-for-character, preserving length and character class, because length is
structure: it determines where a run ends and therefore whether the bug reproduces.

Two version markers are recorded into every trace:

| Marker | Changes when | Maintained |
|---|---|---|
| `redactorVersion` | the redaction *algorithm* changes | by hand |
| `allowlistFingerprint` | the *vocabulary* changes | automatically, hashed from the live allowlist |

The fingerprint is derived rather than declared precisely so it cannot be forgotten: editing
`STRUCTURAL_WORDS` changes it whether or not anyone thought about the corpus.

## 3. Validation

Automated, and run **before the trace is written** — a refused capture never reaches disk, because a
rejected file sitting in the working tree is one `git add` from being committed by someone who did
not read the output.

`TraceValidator` checks four things:

1. **No unmasked PII** — email addresses, Indian mobile numbers, IFSC branch codes. Same shapes
   `scripts/check-fixture-hygiene.sh` blocks commits on, so the two cannot drift apart. **Blocker.**
2. **Required structural evidence preserved** — every `--requires` token still present after
   redaction. **Blocker.** This is the check that would have caught the deposit-vocabulary
   incident.
3. **Still parses into a table** — a trace yielding no sections is a file, not evidence of a
   layout. **Blocker.**
4. **Capability claims earned** — the capabilities the trace says it protects actually activate on
   it. **Review**, not blocker: a trace can legitimately be captured before the capability it will
   protect exists.

Output is a `TraceQualityReport` — provenance, structure, capabilities claimed vs activated,
evidence preserved vs missing, PII scan, review items, verdict. Approving a capture is a decision
made from that report, not a scan of thousands of coordinate lines.

## 4. Regeneration

**A trace must be re-evaluated whenever the redaction allowlist changes.** Not "should be
remembered" — `TraceCorpusHealthTest` compares every committed trace's recorded fingerprint against
the current one and names the ones that no longer match, on every build.

It **reports** rather than fails, deliberately: a stale trace is not broken, it is a re-capture that
needs the original document, and the person running the build may not be the person holding it.
Failing a build for something only someone else can fix trains people to ignore the failure.

The one hard failure is unmasked PII in a committed trace. That is not a maintenance item.

**Re-capture requires the original document.** A committed trace cannot be un-redacted — masked
tokens are gone. If the document is no longer available, the trace is permanently limited to
whatever evidence survived, and that limitation must be stated in whatever test depends on it
rather than papered over.

## 5. Retirement

Retire a trace when:

- the capability it protects has been removed, or
- it has been superseded by a re-capture of the same document, or
- its evidence was lost and the source document is gone, **and** a synthetic fixture now covers the
  structure it used to.

Deleting a trace deletes evidence. Record in the commit message what capability it protected and
what now covers that ground — otherwise the corpus shrinks without anyone being able to say what
was given up.

## 6. Mapping back to capabilities

Every trace carries its own answer to "why is this file here":

```
# capabilities: FINANCIAL_PRODUCT_DISCOVERY, COMPOSITE_STATEMENT
# regressions: #212, #247
# requiredHeaders: Maturity Date, Rate of Interest, Narration
# motivation: Combined statement containing savings + FD + RD under one relationship number.
```

Six months later this is the difference between a fixture someone maintains and a fixture nobody
dares delete.

---

## Current corpus status

All three committed traces are **legacy v1** — captured before metadata existed, so their redaction
provenance cannot be established, and all three report stale. This is accurate rather than
alarming: they were captured under an allowlist that had no deposit vocabulary, which is exactly
the damage that motivated this document.

| Trace | Status | Notes |
|---|---|---|
| `hdfc-composite-deposit-schedules` | v1, evidence damaged | `Maturity Date` → `Xxxxxxxx Date`. Cannot prove deposit classification; a synthetic fixture stands in. |
| `hdfc-txn-date-narration-header` | v1, provenance unknown | No known damage; unverifiable. |
| `bob-repeated-account-banner` | v1, provenance unknown | No known damage; unverifiable. |

Re-capturing these needs the original PDFs and is tracked as item 1 of
[`import-engine-improvement-proposal.md`](../../proposals/import-engine-improvement-proposal.md).
