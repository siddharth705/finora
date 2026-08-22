# Design: anchor-aware same-day transaction ordering in `BalanceChainUtil`

**Status:** design decided (§7). No implementation yet — this document records what to build, not a
completed build; implementation should not start on the strength of this doc alone without a
separate explicit go-ahead, the same gate every other Phase 2 design in this project has used.

**Evidence base:** [same-day-reversal-closing-balance-investigation.md](same-day-reversal-closing-balance-investigation.md)
— every claim about the real document, the algorithm walkthrough, and the `StatementTotalsValidator`
misdiagnosis below is sourced from that document's traced evidence, not re-derived here.

**Naming:** tracked as **Phase 2G — Balance Sequence & Reconciliation Reliability**, not a new 2F —
decided in §7, decision 1. The living plan's existing 2F (multi-page continuation) is untouched.

---

## 1. Problem statement

> When a statement's boundary date (its earliest or latest transaction date) carries more than one
> transaction, can `BalanceChainUtil` always determine which one is chronologically first/last from
> the balance chain alone — and when it genuinely cannot, does it say so, or does it guess?

Today it guesses, silently. `BalanceChainUtil.first()`/`last()` exist specifically because file
order within a single day cannot be trusted (the class's own doc comment cites a real PNB ONE
statement listed newest-first). Their fallback — `min`/`max(balanceAfter)` when the internal
chain-walk finds no unique answer — is itself an unstated, unvalidated assumption: that the day's
true first (last) transaction is whichever leaves the lowest (highest) balance. That assumption is
false whenever a boundary day's last transaction is the one pulling the balance back down from an
earlier same-day peak, which is exactly what a same-day reversal (a deposit immediately offset by a
withdrawal of the same amount, or the reverse) produces. The investigation doc confirms this
happening on a real document, and confirms the resulting wrong `closingBalance` is not merely
"off" — it is confidently wrong, propagated as fact, with no signal anywhere that the derivation was
uncertain.

## 2. Existing algorithm limitation

```
same-day observations
        |
        v
for each candidate: does some other candidate's
implied pre-balance equal my post-balance?
        |
   +----+----+
   |         |
  yes        no  <- unique candidate with no successor: TRUE last
   |
(every candidate has an apparent successor,
 or the loop found none uniquely)
   |
   v
fallback: max(balanceAfter)   <- UNVALIDATED ASSUMPTION:
                                  "last" == "day's highest balance"
```

The chain-walk is sound for a day whose transactions form a genuine one-directional sequence. It
breaks down specifically when some subset of the day's transactions nets to zero: a full reversal
closes a numeric loop, so the earlier transaction's own pre-balance and the later transaction's
post-balance become the same value, and the walk cannot tell which candidate that shared value
belongs to. The fallback it drops into was never designed to arbitrate this case — it was, at best,
a plausible default for the *ordinary* multi-candidate case, not a considered answer for the
closed-loop case, and the investigation doc's real-document evidence shows it choosing the day's
peak balance as "last" when the true last transaction is the one that pulled the balance back down.
Neither `first()` nor `last()` currently distinguishes "I found a unique, provably correct answer"
from "I fell back to an assumption" — both return a plain value, indistinguishable to every caller.

## 3. Real-document evidence

Summarized from the investigation doc; full derivation and the exact code walkthrough are there.

- One real document's boundary date carries a credit of amount **X** immediately followed by a
  debit of the same amount **X** (net zero for the day). `BalanceChainUtil.last()`'s fallback
  returns the credit's balance (the day's peak) as `closingBalance`; the true answer is the debit's
  balance (the day's trough, matching the document's own true closing position).
- **Compounding effect, newly noted here**: `StatementTotalsValidator`'s own corroboration
  heuristic — comparing the *last staged row's own stated balance* (trustworthy here, since this
  document's row order happens to match chronological order) against the buggy `closingBalance` —
  concludes the two disagree and reports `suspectedCause: TRANSACTIONS`, actively misdirecting
  whoever reads the finding toward the wrong subsystem. The transactions are correct; the derived
  closing balance is not. See the investigation doc's addendum.
- `BalanceChainUtilTest.java` (existing, 7 tests) has no case for a same-day closed loop today —
  confirmed by inspection. Its newest-first and oldest-first cluster tests are all monotonic
  (balance moves in one direction across the cluster); none nets to zero.

## 4. Proposed architecture

### 4.1 Anchor model — decided (§7, decision 2)

`first()`/`last()` currently see only the same-day group itself. Breaking the closed-loop ambiguity
requires information from outside that group. **Decided: seed from the statement's own stated
opening balance, then propagate day by day, each resolved day's closing balance becoming the next
day's anchor:**

```
Statement opening balance
          |
          v
Day 1 transaction sequence
          |
          v
Day 1 closing balance
          |
          v
Day 2 transaction sequence
          |
          v
Day 2 closing balance
          |
          v
         ...
```

**Explicitly not an anchor: the statement's own *printed* closing balance.** It is tempting to use
it — it is, after all, "known" — but doing so would make ordering resolution consult the exact value
`StatementTotalsValidator` independently checks the resolved sequence against, which makes that check
circular (a sequence chosen because it reproduces the printed total will always "agree" with the
printed total, regardless of whether the chosen order is actually correct). The two layers must stay
independent:

```
BalanceSequenceResolver
    answers: "What sequence of transactions produces this balance progression?"
    (uses only the statement's own opening balance as its anchor)

StatementTotalsValidator
    answers: "Does the resulting sequence agree with the statement's printed totals?"
    (an independent check the resolver's answer is judged against, not fed by)
```

This also settles what happens with no printed opening balance at all (some real documents state
none): there is no anchor for day 1, and day 1 resolves however the existing internal chain-walk
already handles a single unanchored group — unique if the day's own transactions form a clean chain,
`BALANCE_ORDER_AMBIGUOUS` (§4.3) otherwise. Every later day still anchors normally, off day 1's own
resolved close, once day 1 itself resolves.

### 4.2 Proposed algorithm direction

```
resolved anchor balance (statement opening balance for day 1,
                          previous day's resolved closing balance for every later day)
        |
        v
same-day group
        |
        v
walk forward from the anchor: does exactly one ordering of the group,
starting from the anchor, reproduce every observation's own stated balanceAfter?
        |
   +----+----+
   |         |
 unique    not unique, or no anchor available
   |         |
 use it   §4.3 — ambiguous, do not guess
```

This does not discard the existing chain-walk — for the ordinary (non-closed-loop) case, the
anchor-based walk and the existing internal walk should agree, and the internal walk remains useful
for day 1 when the statement states no opening balance at all (§4.1). The anchor changes what happens
in the case the internal walk cannot resolve: instead of an unvalidated `max`/`min` guess, an
anchor-confirmed unique ordering, or an explicit unresolved state.

### 4.3 Ambiguity must be a first-class, visible outcome — never a silent guess — decided (§7, decision 4)

Per the owner's own framing, matching this codebase's already-stated philosophy
(`ImportVerifier`'s own doc comment: *"a weighting policy invented before there is anything to
calibrate it against is a guess with an authoritative appearance"* — the same objection applies to
an unvalidated tie-break presented as a definite answer): **unknown is better than confidently
wrong.** When no anchor is available and the internal chain-walk finds more than one candidate with
no apparent successor/predecessor, the correct outcome is **not** `max`/`min(balanceAfter)`. It is an
explicit, surfaced "ordering uncertain" state — `BALANCE_ORDER_AMBIGUOUS`, mirroring
`HeaderReconstructionFinding`'s existing `TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN` precedent
(§4.3 of the header-reconstruction design): a no-forced-guess failure mode this codebase already has
one working example of, not a new pattern.

**Decided: suppress, don't flag-and-guess.** `closingBalance`/`openingBalance` are withheld entirely
for a section whose sequence resolves ambiguous, exactly the same path `StatementTotalsValidator`
already takes when a statement prints no balance at all (`NOT_APPLICABLE`, not a false
`FAILED`/`VERIFIED`):

```
No unique sequence
        |
        v
balance resolution unavailable
        |
        v
StatementTotalsValidator follows its existing NOT_APPLICABLE path
```

Rejected: populating `closingBalance` with a best-effort value while flagging it unconfirmed for a
separate consumer to check. This codebase already has a cautionary precedent for that shape failing —
`HeaderReconstructionFinding` sat computed and unread by anything downstream for a real stretch of
this project's history. A value that is present but flagged untrustworthy only works if every
consumer remembers to check the flag before trusting it; suppression removes the value instead of
trusting every future caller to check.

### 4.4 API shape and output contract — decided (§7, decision 3)

The owner's own caution: do not assume `BalanceChainUtil.first()`/`last()` should simply grow an
`anchorBalance` parameter without checking who else calls them and why they are stateless today.

**Decided: a new component, `BalanceSequenceResolver`. `BalanceChainUtil` is not modified.**

```
BalanceChainUtil (unchanged)
    Input:  a single same-day group of transactions
    Output: that group's internal ordering, via its existing ChainLink-based arithmetic

BalanceSequenceResolver (new)
    Input:  the whole statement's transactions + the statement's stated opening balance
    Output: an ordered transaction sequence, with anchor source, ambiguity status, and evidence
            (BalanceSequenceResolution, below)
```

Rejected: adding a `BalanceChainUtil.resolveStatement()`-style method directly on the existing
utility — folding whole-statement, cross-day anchor propagation into a class whose entire premise is
being a small, stateless, single-group calculation helper would make the abstraction misleading. The
resolver is new code that composes with `BalanceChainUtil`'s existing `ChainLink` arithmetic rather
than replacing or absorbing it — the same relationship `HeaderQualityValidator` has to
`looksLikeHeaderRow` in the header-reconstruction design: a new, once-per-statement-scoped decision
sitting on top of an unchanged, lower-level primitive.

Both production call sites (`StatementValidator` for CSV, `PdfPreviewGenerator` for PDF) must move
to `BalanceSequenceResolver` together, in the same change — not incrementally, one path first. This
class's own doc comment already names what happens when the two paths drift: "that duplication is
what let this exact bug exist in two places at once and only get fixed in one." Migrating one call
site and leaving the other on the old `first()`/`last()` fallback would recreate exactly that.

**Output contract, required before implementation starts.** The resolver must not return a bare
`List<Transaction>` — nothing downstream could then tell a confidently-resolved sequence from a
lucky-guess one, which is the exact ambiguity this whole design exists to eliminate. A structured
result, in the same spirit as `VerificationFinding`/`HeaderReconstructionFinding`:

```
BalanceSequenceResolution
    orderedTransactions   -- the resolved sequence (or the input, unordered, if unresolved)
    anchorSource          -- STATEMENT_OPENING_BALANCE | NONE
    ambiguityStatus       -- UNIQUE | AMBIGUOUS
    evidence              -- structured explanation, in the tiered style §5/§6 of the
                             header-reconstruction design already establishes for this codebase
                             (why this ordering, or why none could be chosen)
```

Example, resolved:

```
anchorSource:      STATEMENT_OPENING_BALANCE
ambiguityStatus:   UNIQUE
evidence:          same-day ambiguity: none; final balance: validated against the anchor chain
```

Example, unresolved:

```
anchorSource:      NONE
ambiguityStatus:   AMBIGUOUS
evidence:          multiple valid same-day sequences; no anchor available to disambiguate
```

Exact field types and where this record lives (a new DTO, or folded into the existing
`ImportDto`/evidence types) is implementation work, not fixed here — the requirement is that the
shape exists and carries anchor source, ambiguity status, and evidence as first-class fields, not
that this exact Java signature is final.

## 5. Interaction with `StatementTotalsValidator` — resolved by §4.1 and §4.3 together

The two layers keep the separation of concerns §4.1 states: `BalanceSequenceResolver` decides what
sequence produces the observed balance progression, using only the statement's own opening balance
as its anchor; `StatementTotalsValidator` independently judges whether that resolved sequence agrees
with the statement's printed totals. Because §4.3 decided to suppress rather than flag-and-pass, this
validator never has to learn a new "ambiguous" concept: an ambiguous section simply presents no
`closingBalance`/`openingBalance`, which is already its existing `NOT_APPLICABLE` path (`"The
statement did not state a closing balance"` — untouched, no new branch needed, since to this
validator an unresolved sequence and a statement that printed nothing are the same absence).

The `suspectedCause: TRANSACTIONS` misdiagnosis (§3) is fixed as a side effect, not by teaching this
validator about ambiguity: once `closingBalance` is only ever populated when the resolver reached a
`UNIQUE` sequence, the value this validator receives is either correct or absent — never confidently
wrong the way today's silent `max`/`min` fallback produces it.

## 6. Regression strategy

Four case categories, plus CSV/PDF parity, before any implementation is considered mergeable —
matching the header-reconstruction design's "falsifiable against real evidence before merge"
standard:

1. **Existing behavior, unchanged.** Every one of `BalanceChainUtilTest`'s current 7 cases (single
   observation, newest-first cluster, oldest-first cluster, the existing "no clean chain"
   fallback-to-earliest-implied-start case) must still pass unmodified — this fix must not narrow
   what already resolves correctly.
2. **The new closed-loop case, corrected.** A same-day (or anchor-adjacent) credit-then-debit (and
   its mirror, debit-then-credit) of equal magnitude must resolve to the textually-last transaction
   as `last()`'s answer when an anchor is available, not the fallback's peak/trough guess.
3. **The genuinely ambiguous case, honestly reported.** The same closed-loop shape *with no anchor
   available at all* (e.g. a single-day statement with only these two transactions and no stated
   opening balance) must produce `BALANCE_ORDER_AMBIGUOUS` (§4.3), not a confident wrong answer and
   not a confident right-by-luck answer either — the point is that the algorithm cannot know it is
   right in this case, so it must not claim to.
4. **Loops wider than one pair.** The 2-transaction case (+X, −X) is the minimum reproduction, not
   the only shape. Also needed: a 4-transaction loop that returns to its starting balance through two
   pairs (+500, −500, +500, −500) and a 3-transaction group that nets to zero without any single
   reversing pair (+1000, −700, −300) — a case the "does some other candidate's implied pre-balance
   match mine" pairwise check in today's algorithm was never even structured to consider, since it
   only ever compares two candidates at a time.
5. **CSV/PDF parity.** Because `BalanceChainUtil` is unchanged and both call sites move to
   `BalanceSequenceResolver` together (§4.4), every new fixture in categories 2–4 must be run through
   both `StatementValidator` (CSV) and `PdfPreviewGenerator` (PDF) and produce the same ordering
   decision — no divergence between the two paths, the exact failure mode this class was extracted to
   prevent in the first place.

## 7. Decisions

All five decided by the owner, no pushback, consistent with this project's already-established
architecture discipline (evidence before automation, no invented confidence scores, no hidden
fallback guesses, keep responsibilities separated) — the same discipline the header-reconstruction
design already applied to a different shared component.

1. **Naming — Phase 2G — Balance Sequence & Reconciliation Reliability, not 2F.** The failure sits
   between extraction and reconciliation, not inside either:

   ```
   Extracted transactions
           |
           v
   Balance sequence resolution   <- the defect is here
           |
           v
   Reconciliation result
   ```

   Table location, boundary detection, and row completeness (2A–2E) are all upstream and unaffected;
   `StatementTotalsValidator`'s reconciliation arithmetic is downstream and already correct. The
   defective layer sits between the two and has no other name in the existing 2A–2H scope table
   besides 2G (§4d: *"extracted transactions vs. statement totals"*). The plan's existing 2F
   (multi-page continuation) is a different problem and stays as-is.
2. **Anchor model — statement opening balance, propagated day by day. Never the printed closing
   balance.** §4.1, finalized above.
3. **API boundary — new `BalanceSequenceResolver`; `BalanceChainUtil` is not modified.** §4.4,
   finalized above, including the required `BalanceSequenceResolution` output contract.
4. **Ambiguity — suppressed, not flagged-and-guessed.** An unresolved sequence yields no
   `closingBalance`/`openingBalance` at all, routing through `StatementTotalsValidator`'s existing
   `NOT_APPLICABLE` path (§4.3, §5).
5. **Corpus sweep — before implementation, narrowly scoped.** Not a full document/metadata
   validation pass — a lightweight diagnostic answering exactly five questions against the real
   corpus:
   1. How many statements contain same-day multi-transaction groups at all?
   2. How many of those contain a closed numeric loop (some subset nets to zero)?
   3. How often is the statement's own opening balance actually available to anchor from?
   4. How often would this design's ambiguous state actually be reached, given (3)?
   5. Do any real loops go beyond the 2-transaction case — matching §6 category 4's 4-transaction
      and 3-transaction non-pair examples?

   Findings from this sweep should update the design (this document) before `BalanceSequenceResolver`
   is implemented, per the recommended sequence: doc → sweep → update design against observed
   frequency → implement.

## 8. Non-goals

- **2E.3 (narration correctness) and 2E.4 (CBI opening-balance claim).** Unrelated; 2E.3 is already
  closed as not-reproduced by the investigation doc, 2E.4 remains a separate, unverified hypothesis.
- **The trace-vs-real-PDF divergence** noted in the investigation doc's own "also observed" section.
  Unexplained, unrelated to balance ordering, not addressed here.
- **A full real-document/metadata validation corpus pass.** §7 decision 5's sweep is deliberately
  narrow (five counting questions against the diagnostic), not a re-verification of every document.
- **Any change to `StatementTotalsValidator`'s core arithmetic.** §5 only concerns how it now never
  receives an ambiguous value in the first place; its opening+credits−debits=closing check itself is
  untouched.
- **Implementation of `BalanceSequenceResolver` itself.** This is a design document. §7's decisions
  are final; building against them is the next step, not part of this document, and per the
  recommended sequence should follow the decision-5 corpus sweep, not precede it.
