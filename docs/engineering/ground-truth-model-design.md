# Design: the ground-truth model

**Status:** design and test specification. No implementation, and none should follow until this
contract is agreed — that ordering is the point, not a formality.
**Implements** [ADR-005](../architecture/adr-005-document-intelligence-contract.md) §5 and §7.
**Precedes** RD/FD extraction and zero-transaction persistence, which land together
([persistence-boundary-design.md](persistence-boundary-design.md) §6).

---

## 1. What this is for, stated as the failure it must catch

Today `Shivani_HDFC.pdf` produces a corpus record reading `sections: 3, rows: 75`, per-section
`[75, 0, 0]`, and a classification of `COLUMNS_AMBIGUOUS`. Two completely different realities produce
that identical record:

| | |
|---|---|
| **A** | Savings has 75 transactions; the RD and FD sections legitimately carry no transaction ledger. **Correct.** |
| **B** | Savings has 75 transactions; the RD and FD exist and their data was not extracted. **A defect that reports success.** |

Nothing in the pipeline can currently tell A from B, so `[75, 0, 0]` is a *recorded baseline* rather
than a *judgeable* result. **The single job of this model is to make that distinction expressible and
checkable.** Every design decision below follows from it.

## 2. Where it lives

Real-corpus ground truth is **outside the repository**, beside the corpus. It is derived from customer
statements and is data of the same sensitivity: expected principals, maturity amounts and masked
identities are customer financial facts whether they sit in a PDF or a YAML file next to one. This is
not a preference — it is the rule established by the 2026-08-08 incident, whose §6.1 finding was that
real customer data must not become a development artefact at all.

**Synthetic ground-truth fixtures live in the repository**, and their job is to test the *mechanism*.
Same split as `scripts/test-corpus-diff.py`, which tests the diff without the corpus. It follows that
ground-truth matching against the real corpus can never run in CI, exactly like
`check-corpus-leakage.py`.

## 3. Entities are keyed by stable id, never by position

```yaml
document:
  fingerprint: <layout fingerprint>        # not a filename; see §9
  institution: <bank>
  pages: <n>
entities:
  - id: savings-primary                    # stable, human-chosen, arbitrary
    expectedPresence: DETECTED
    expectedProduct: SAVINGS
    ...
  - id: rd-1
  - id: fd-1
```

Section identity is unreliable — `corpus-diff.py` suppresses positional comparison the moment section
count changes, and a position-indexed ground truth would inherit that same weakness. So expected
entities carry ids that mean nothing to the parser, and pairing them to observed sections is an
explicit, fallible step (§5) rather than a `zip`.

## 4. The per-entity record

```yaml
- id: fd-1
  expectedPresence: DETECTED | ABSENT
  expectedProduct: FIXED_DEPOSIT
  expectedIdentity:                        # OPTIONAL -- see below
    accountNumberMasked: <masked>
  expectedTransactions: <n> | NOT_APPLICABLE | NOT_YET_ESTABLISHED
  expectedAttributes:                      # only what the document actually states
    principalAmount: <present | value>
    maturityDate: <present | value>
    maturityAmount: <present | value>
  zeroTransactionsLegitimate:              # see 4.2 -- never a bare boolean
    value: TRUE | FALSE | UNKNOWN
    evidence:
      source: DOCUMENT | GROUND_TRUTH | ABSENT
      pages: [<n>]
      reason: <what in the document supports this>
  evidence:                                # why we assert this entity exists at all
    pages: [<n>]
    reason: <the document's own vocabulary, not its values>
```

### 4.1 Identity is optional, and must never be fabricated

A deposit may carry no masked number. Product type plus principal plus maturity date is a legitimate
basis for pairing; where nothing pairs, the matcher returns `AMBIGUOUS` (§5). It must never invent an
identifier to make a match succeed — `ProductIdentity.forDeposit` already refuses to hash three nulls
into a collision for the same reason.

### 4.2 `zeroTransactionsLegitimate` carries provenance

A bare boolean derived from parser output is the original defect with a field name attached:
`rows == 0 → therefore zero is legitimate` is precisely the inference that must not be available. So:

- `UNKNOWN` is **first-class** and **never defaults to `TRUE`**. An entity with zero transactions and
  `UNKNOWN` legitimacy is a review item, not a successful import.
- It must not be inferred from `expectedProduct`. A term deposit *can* list interest credits, so "FDs
  have no transactions" is a guess dressed as a rule. The assertion is per-entity, per-document.
- `source: ABSENT` means nobody has looked. That is different from `value: FALSE`.

### 4.3 Establishment is per field, not per entity

Ground truth is built by a human reading the document, and understanding arrives unevenly. For
Shivani's savings section we can state `DETECTED / SAVINGS` immediately; whether the transaction count
is genuinely 75 requires counting by hand and is *not established* until someone does. So
`NOT_YET_ESTABLISHED` is a legal value on any field, and an unestablished field is never treated as
agreement.

**Ground truth is never derived from parser output.** Doing so would make today's behaviour the
definition of correct — the constraint `DocumentClassification.Signals` already records for
`expectedTransactions`.

## 5. Matching: four outcomes, and one of them is "I don't know"

Observed sections are paired to expected entities by identity where available, then by product type
plus attributes. Never by index.

| Outcome | Meaning |
|---|---|
| `MATCHED` | paired; per-field comparison follows |
| `MISSING` | expected, not found — **the Shivani defect** |
| `UNEXPECTED` | found, not expected — a real discovery *or* a spurious section |
| `AMBIGUOUS` | cannot pair without guessing |

`AMBIGUOUS` is a first-class result, not an error. Two zero-transaction deposits with no identity and
the same product type are genuinely indistinguishable, and reporting that honestly is correct
behaviour. Guessing which is which is how transactions get attributed to the wrong product.

A `MATCHED` entity then compares `expectedTransactions` against observed rows, `expectedProduct`
against `detectedProduct`, and each `expectedAttributes` entry against what was extracted — with
`NOT_YET_ESTABLISHED` fields skipped rather than passed.

## 6. The test that decides whether this model works

**Two ground-truth files, one observed record.** Both describe `Shivani_HDFC`; the observed output is
byte-identical `[75, 0, 0]` in both cases.

| Ground truth | `zeroTransactionsLegitimate` for `rd-1`/`fd-1` | Required verdict |
|---|---|---|
| **A** | `TRUE`, evidence: no transaction ledger printed for these products | **PASS** |
| **B** | `FALSE` — the document prints an RD installment history | **FAIL**, `rd-1` under-extracted |

If the model cannot separate A from B, nothing downstream can, and this document has failed. That is
the first test to write, before any others.

Three more that pin the states most likely to collapse:

- An expected entity that is **not detected at all** → `MISSING`, and the import must not report
  success. Distinct from a detected entity with zero rows.
- An entity with zero rows and `zeroTransactionsLegitimate: UNKNOWN` → **review**, never pass. Plus a
  guard asserting `UNKNOWN` cannot resolve to `TRUE` implicitly anywhere in the matcher.
- Two indistinguishable zero-transaction deposits → `AMBIGUOUS`, not an arbitrary pairing.

## 7. Priority order for establishing real ground truth

Highest information per unit of human effort:

1. **`Shivani_HDFC`** — the composite case. Produces the discrimination test in §6 and the acceptance
   criterion for RD/FD persistence.
2. **The two confirmed under-extraction cases** — 1 row from 4 pages, and 3 rows from 9. Ground truth
   turns each from a suspicion into a measurable shortfall.
3. **The two zero-row statements** — one loses text before positioning, the other positions correctly
   and fails at row parsing. Ground truth defines what *should* come out, making them the target for
   the layout work.
4. **A reconciliation failure on a single page** — the row/page heuristic cannot fire there, so only
   ground truth can judge it.

## 8. Success criterion for the work this unblocks

Not "row count increased". The acceptance condition for RD/FD extraction and persistence is:

> **Savings unchanged**, RD detected, FD detected, correct attributes persisted, and **no `AMBIGUOUS`
> entity silently accepted.**

The first clause is the one that needs the instrument. Without per-section ground truth, a change that
quietly re-attributed the savings section's 75 rows would look identical to success.

## 9. What this model deliberately does not do

- **No filenames in the matcher.** Ground truth is per-document data, so a document key is
  unavoidable — but it belongs in the ground-truth file, never in pipeline or diff logic (ADR-004 §4).
  Keying on layout fingerprint rather than filename is preferred where it is stable enough, since real
  filenames carry personal names.
- **No expected row positions, and no expected fingerprint as an assertion.** Both would break on a
  legitimate refactor.
- **No severity ranking.** Whether `MISSING` is worse than a transaction-count shortfall is an
  operational judgement, not a property of the data.
- **No inference from `expectedProduct` to `zeroTransactionsLegitimate`.** Stated twice because it is
  the shortcut most likely to be taken under time pressure, and taking it reintroduces the defect.
