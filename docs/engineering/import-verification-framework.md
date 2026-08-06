# Import Verification Framework

**Status: direction, partially built.** One layer exists (`BalanceChainValidator`, `fe1302e`). This
records the shape the rest should take, and one correction to what already shipped.

---

## The promise

> Finora does not ask users to verify imported data. Finora verifies imported data against evidence
> contained within the statement itself. When verification succeeds, the import is marked verified.
> When it cannot be completed, Finora names the specific rows or conditions that could not be
> proven, rather than asking the user to review the whole statement.

That is a different product claim from "we support 40 banks". A competitor can add a parser. It is
much harder to say **"this import is mathematically verified"** — and the claim gets stronger with
every bank rather than being re-earned per bank.

The reason it is achievable here at all: **bank statements are self-proving documents.** Unlike most
parsing problems, the ground truth ships inside the file. Every row prints the balance after it, and
most statements print their own debit/credit totals. We were not using either.

## What changed in thinking

The pipeline assumed each stage's output was correct because the previous stage did not throw:

```
PDF → detect layout → extract table → normalize → guess direction → import
```

Every arrow is an assumption. The question was *"can we parse this?"* It should be *"can we prove
this import is faithful to the statement?"*

This is not hypothetical. A real HDFC statement imported three withdrawals as amount `0` and a
deposit in the wrong direction. Every stage reported success. Nothing compared the numbers to
anything, so nothing could have noticed.

## Architecture: validators emit facts, one engine judges

**This corrects `BalanceChainValidator` as first written.** It returns its own verdict
(`VERIFIED/WARNING/FAILED`). That does not compose: a second validator would invent its own severity
scale, and three validators each reporting "WARNING" cannot be combined into an answer. Severity is
a judgement about the whole import, and it belongs in one place.

Validators should state what they observed:

```
RunningBalanceReconciled   PASS   rowsChecked=124  failures=[]
StatementTotalsMatch       FAIL   difference=436.00
AmountColumnAmbiguity      FAIL   row=17
AccountNumberMatched       PASS
```

An aggregator combines them into VERIFIED / WARNING / FAILED. Two consequences worth stating:

- A validator becomes independently testable and independently addable. Nothing needs to agree in
  advance about what "warning" means.
- Weighting becomes a single reviewable decision rather than a policy scattered across validators.

`BalanceChainValidator.Outcome` should therefore shrink to a fact, and the aggregation move out.
Doing this **before** a second validator exists is the point — this repository's own audit history
is largely a catalogue of what happens when a rule gets copied instead of extracted.

### Correction: `ConfidenceEngine` is not the aggregator

An earlier draft of this document said the existing `ConfidenceEngine` should aggregate these
facts. That is wrong, and checking it is what settled the sequencing question. `ConfidenceEngine`
answers *"how confident are we that merchant X belongs to category Y"* -- a category's share of a
merchant's confirmation history. Import verification answers *"how confident are we that this
import faithfully represents the source document"*. Different domains; reusing it would leave
"confidence" meaning two unrelated things in one codebase.

So the aggregator does not exist, and building one now would mean inventing weights, precedence and
policy for a single validator with nothing to weigh it against. It waits until a second validator
exists. What does NOT wait is the wire format: `VerificationReport` already carries a list of
findings, so adding validators later appends to it and changes nothing else.

### Deferred: a separate `severity` on each finding

Considered and not added. The argument for it is real -- `outcome` answers "did this rule pass",
`severity` answers "how much does that matter", and those genuinely diverge for a rule that fails
without it being important (a layout heuristic, say).

Two reasons to wait, and the first is the general rule:

- **Generalise now what would be BREAKING later; defer what is ADDITIVE.** Replacing a row-shaped
  payload with `details` had to happen before a second validator, because changing it afterwards
  breaks every client. Adding a `severity` field later breaks nobody. That asymmetry is the whole
  criterion, and it is worth applying deliberately rather than generalising everything on instinct.
- **Today it would duplicate `outcome`.** The balance chain already distinguishes WARNING from
  FAILED, which IS a severity judgement -- "a few rows disagree" versus "this column is being
  misread". A second field would carry the same information under another name, which is the
  two-sources-of-truth problem that removed the report's overall status.

Revisit when a rule exists whose failure is genuinely low-stakes. At that point there is something
to calibrate the scale against, instead of one producer and a guess.

## Evidence quality, not one-off checks

Rather than adding a bespoke check per ambiguity, validators should grade the evidence behind a
conclusion:

| Grade | Meaning |
|---|---|
| HIGH | account number matched, balance chain reconciled |
| MEDIUM | inferred from bank + account type |
| LOW | conflicting amount columns, conflicting dates, conflicting balances |

Two populated mutually-exclusive amount columns is then not a special case — it is LOW-grade
evidence, and it composes with everything else automatically.

## The layers, and where they stand

| Layer | Status | Note |
|---|---|---|
| 1. Structural — required columns, dates, balances parsed | partial | `rejectIfNothingWasExtracted`, header detection |
| 2. Row validity — exactly one of debit/credit, etc. | partial | rows drop with reasons |
| **3. Arithmetic — `previous ± amount == balance`** | **built** | `BalanceChainValidator` |
| 4. Statement totals — `opening + credits − debits == closing` | not built | **anchors layer 3, see below** |
| 5. Summary — the bank's own printed totals and counts | not built | HDFC prints `Debit 538.00, Count 3` |
| 6. Semantic — direction agrees with balance movement | free | falls out of layer 3 |
| 7. Cross-field — mutually-exclusive columns both populated | not built | becomes evidence grading, above |
| 8. Confidence engine | not built | see the correction below -- `ConfidenceEngine` is unrelated |
| 9. Golden traces | built | `GoldenOutputSnapshotTest`, `trace-capture.sh` |
| 10. Production telemetry | not built | V55 explanations are the precedent |

**Layer 6 needs no code.** A deposit recorded as an expense moves the balance the wrong way, so it
surfaces as a discrepancy of *twice* the amount — louder than the transaction merely being absent.

**Layer 4 is not an extra check, it closes layer 3.** Chaining consecutive pairs cannot test the
first row, because nothing precedes it. Run against the motivating statement, the pair-only check
reported VERIFIED while its opening deposit was still typed EXPENSE — the error sat in the one
position the chain is blind to. `validate(rows, openingBalance)` anchors it. The anchor is nullable
on purpose: a *wrong* opening balance would flag a correct row, and one false accusation costs more
trust than several missed catches.

## Layout certification

Today a successful import teaches the system nothing. Persisting per-layout outcomes changes that:

```
Layout fingerprint   A72B
Verified imports     582
Verification rate    99.8%
```

The layout fingerprint already exists (`DocumentContext.buildFingerprint()`, and V39's
`layoutFingerprint` column). This is mostly a matter of recording the verdict against it.

What it buys, in order of value:

1. **Regression detection before users report it** — a layout whose verification rate drops after a
   parser change is a regression, visible without anyone filing anything.
2. **Trust shown before import** — "verified on 582 previous imports of this layout".
3. **Prioritisation by evidence** — which bank formats actually fail, rather than which ones people
   happened to complain about.

Point 1 is the real prize, and it is the same argument as the `durationMs` metric added to
reconciliation: collect the evidence as a by-product of ordinary use, so the next investigation
starts with data instead of a synthetic benchmark.

## Sequencing

1. **Wire layer 3 into the preview.** Highest return, no parser changes. The verdict is useless
   while nothing displays it.
2. **Layer 4**, which closes layer 3's first-row hole.
3. **Refactor to facts + `ConfidenceEngine`** — before a second validator exists, not after.
4. **Layer 7 as evidence grading.**
5. **Persist per-import and per-layout** (layer 10 / certification).

Each step is independently useful, and none requires the next to be worth having.

## What this must not become

- **A gate.** Verification classifies; the user decides. A validator that refuses an import turns
  every false positive into "Finora cannot read my statement" — worse than the failure it prevents,
  and statements legitimately defeat these checks (mid-statement summary lines, no balance column).
- **A confidence number nobody can explain.** Every verdict must decompose into the facts that
  produced it, for the same reason V55 stores reconciliation explanations: a score without its
  evidence is not reviewable, and cannot be argued with when it is wrong.
