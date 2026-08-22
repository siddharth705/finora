# Same-Day Reversal Closing Balance — Investigation

Evidence-only investigation, no production code changed. Triggered by verifying the owner's own
2E.3 framing (`docs/project-management/plans/project-plan-v1.0.md`, §4d: *"ICICI savings description
is wrong for 11 rows"*) against the real corpus, per that section's own instruction to treat the
claim as an unverified hypothesis rather than an established fact. The claim as stated does not
hold; a different, real, precisely root-caused defect was found in the course of checking it, in
`BalanceChainUtil` — a component shared by both the PDF and CSV import paths — not in narration
extraction. Scope note: this document intentionally never quotes any literal value (amount, date,
serial number) from the real statement it was found on, per this project's standing "describe the
shape, never the literal value" discipline for real customer documents; every number in what
follows is symbolic.

## Result

| Question | Finding |
|---|---|
| Is the ICICI savings statement's narration wrong for any of its 11 rows (2E.3's original claim)? | **No.** All 11 real transactions extract with well-formed, correctly-separated narration on current `main`. Not a Phase 2E.2 effect — this document's header shape falls outside the SBI-scoped reconstruction engine entirely; every column recovers via pre-existing mechanisms. |
| Does the statement reconcile (`STATEMENT_TOTALS`)? | **No — and this is real.** The printed closing balance does not match the balance implied by the 11 extracted transactions. Root-caused to a bug in `BalanceChainUtil.last()`, not to a missing or misread transaction. |
| Is this a 2E.3 (narration correctness) issue? | **No.** It is a reconciliation/closing-balance defect — squarely 2G's territory (§4d: *"Transaction reconciliation — extracted transactions vs. statement totals... existing validators can likely become the evidence layer"*), surfaced incidentally while checking a 2E.3 claim that turned out not to be true. |

## Method

Ran `PdfPipelineDiagnostic` (`backend/src/test/java/com/finora/imports/pdf/PdfPipelineDiagnostic.java`,
the project's standing generic diagnostic tool) against the real, unredacted ICICI savings PDF from
the real corpus (`~/Downloads/Bank statement/Savings accounts/`), on current `main` with 2E.2
already merged. Cross-checked against the document's ground-truth JSON
(`~/Downloads/Bank statement/ground-truth/ICICI saving.json`), which independently confirms 11 real
transactions and documents a *different*, already-closed mechanism (a 3-tier header composition
gap, closed by Phase 2E.2's pre-existing recovery methods — not the subject of this doc). A
temporary, unpersisted probe (created, run, and deleted within this session; never committed) dumped
each bucketed row's structural fields — serial number, date, which of the two amount columns was
populated, and running balance — to confirm the mechanism precisely rather than inferring it from
aggregate totals alone.

## Finding 1: the 2E.3 narration claim does not match current reality

The pipeline stages all 11 real transactions from this document's single section. Every row's
narration field is well-formed: non-empty, structured, and consistent with that row's own
transaction-reference shape. `COLUMN_AMBIGUITY` and `ROW_ACCOUNTING` both report `VERIFIED` —
every located row has an accounted-for fate, and no column bucketing collision was detected.

Nothing in this pass reproduces "description is wrong." The owner's original framing predates this
session's Phase 2 work (the ground truth file itself is dated before Phase 2 started) and, per this
project's own Phase 2D finding, no description-accuracy check has ever actually been run against
this document — the claim was carried forward without verification, which is exactly why §4d
flagged it as a hypothesis rather than a fact. **Recommend closing 2E.3 as not reproduced**, rather
than carrying it forward as open work.

## Finding 2: a real closing-balance defect, root-caused

### The transactions in question

The statement's final calendar day (its `statementPeriodEnd`) carries exactly two transactions,
consecutively:

1. A **credit** of some amount **X**, taking the running balance from a pre-existing value **B**
   up to **B + X**.
2. A **debit** of the same amount **X**, immediately after, taking the balance back down from
   **B + X** to **B**.

Both are real, both extract correctly, and each row's own balance is internally self-consistent
with its immediate predecessor — which is why `BALANCE_CHAIN` reports `VERIFIED` (it only checks
that each row's own arithmetic is locally correct, never which row is chronologically last). This
same-day-round-trip shape (money in, then straight back out, netting to zero) is an ordinary,
realistic banking pattern — a reversal, a same-day transfer pair, a collect-then-payout — not a
contrived edge case.

### Where the bug is

`backend/src/main/java/com/finora/imports/BalanceChainUtil.java` exists specifically because file
order within a single day cannot be trusted to reflect transaction order (its own doc comment cites
a real PNB ONE statement listed newest-first). For a same-day cluster, `last()` is supposed to find
the one observation with no successor:

```java
public static <T extends ChainLink> T last(List<T> sameDayGroup) {
    if (sameDayGroup.size() == 1) return sameDayGroup.get(0);
    for (T candidate : sameDayGroup) {
        boolean hasSuccessor = sameDayGroup.stream().anyMatch(other -> {
            if (other == candidate) return false;
            return other.balanceAfter().subtract(other.signedAmount()).compareTo(candidate.balanceAfter()) == 0;
        });
        if (!hasSuccessor) return candidate;
    }
    return sameDayGroup.stream().max(Comparator.comparing(ChainLink::balanceAfter)).orElse(sameDayGroup.get(0));
}
```

Applying this to the credit/debit pair above (call them **A** = the credit, ending at `B + X`, and
**B'** = the debit, ending at `B`):

- **Does A have a successor?** Check B': B'.balanceAfter − B'.signedAmount = `B − (−X) = B + X`,
  which equals A.balanceAfter (`B + X`). **Yes** — correctly, since B' genuinely does follow A.
- **Does B' have a successor?** Check A: A.balanceAfter − A.signedAmount = `(B + X) − X = B`, which
  equals B'.balanceAfter (`B`). This also evaluates **true** — but it is **not actually true**; A
  precedes B', not the other way around. The equality holds only because a full round-trip
  necessarily closes a numeric loop: the earlier transaction's own *pre*-balance and the later
  transaction's *post*-balance are both, definitionally, the same value **B**. The predicate cannot
  tell "B' comes after A" from "A comes after B'" using balance arithmetic alone once the group's
  net effect is zero.

Neither candidate is found to have "no successor," so the loop falls through to the fallback:
`max(balanceAfter)`. That picks **A** (balance `B + X`, the day's peak) as "last" — the wrong
answer. The true last transaction is **B'**, ending at `B`, the day's trough. `closingBalance` is
then read directly off whichever candidate `last()` returns
(`PdfPreviewGenerator.buildDetectedAccountInfo`: `closingBalance = trueLastOfDay.balance()`), so the
reported closing balance ends up exactly **X** too high — which is precisely the shortfall
`STATEMENT_TOTALS` reports as a `FAILED` `TRANSACTIONS`-cause finding.

Confirmed arithmetically, not just structurally: recomputing the statement's implied closing
balance using B' (the debit) as the true last transaction reproduces the document's own printed
closing balance exactly. Recomputing using A (the credit, the fallback's actual pick) reproduces the
`STATEMENT_TOTALS` finding's reported (wrong) value exactly, off by precisely **X**. This is a
closed-form confirmation of the mechanism, not an inference from correlation.

### Why the fallback is wrong in general, not just here

The `max(balanceAfter)` (and `first()`'s mirror-image `min`) fallback silently assumes a day's true
last (first) transaction is whichever leaves the *highest* (*lowest*) balance. That assumption only
holds if a day's net transactions move monotonically in one direction. It is false for any day
mixing credits and debits where the last transaction happens to be the one dragging the balance back
down from an earlier peak that same day — exactly what happened here. The primary chain-walk (the
`for` loop above it) is theoretically sound for a day whose transactions form a genuine one-way
chain; it specifically breaks down when some subset of the day's transactions nets to zero and
closes a loop back onto a shared balance value, which the round-trip case above is the simplest
instance of.

### Why this belongs in `BalanceChainUtil`, not `PdfPreviewGenerator`

`BalanceChainUtil`'s own doc comment states it was extracted as a shared utility *specifically*
because the CSV path (`StatementValidator`) and the PDF path (`PdfPreviewGenerator`) each used to
have their own independent, buggy "earliest/latest by file position" logic, and fixing one without
the other left the second silently wrong. The same risk applies here: a fix scoped only to the PDF
call site would leave the CSV path carrying the identical defect, unfixed, for the identical reason
this class was created in the first place.

## What this is not

- **Not a 2E.2 effect.** The SBI-scoped header reconstruction engine never engages on this document
  — its header shape doesn't match `nonBlankCount(above) == 1`. This finding is entirely orthogonal
  to this session's earlier Phase 2E.2 work.
- **Not evidence that 2E.4 (the CBI opening-balance claim) has the same cause.** No CBI investigation
  was performed here; 2E.4 remains a separate, still-unverified hypothesis per §4d.
- **Not a fix.** No production code was changed. The correct repair likely needs `first()`/`last()`
  to accept an anchor balance from outside the same-day group (the previous day's closing balance,
  for `last()`; the statement's own stated opening balance, for `first()`) to break a closed-loop tie
  correctly, rather than resolving it from the group's own internal values alone — but that is a
  design decision on a shared, two-call-site utility, not something to implement unilaterally
  off the back of one document.
- **Not corpus-swept.** This was root-caused against the one real document that surfaced it while
  checking 2E.3. Whether any other document in the real corpus has a same-day cluster with the same
  closed-loop shape (on either its opening or closing boundary date) has not been checked. `first()`
  carries the identical theoretical flaw but was not exercised on this document, since its earliest
  date had only a single transaction.

## Addendum: the bug is actively misdiagnosed downstream, not just wrong

`StatementTotalsValidator` already has a corroboration heuristic for exactly this situation:
when its own opening/closing/rows arithmetic doesn't balance, it separately checks the *last
staged row's own stated balance* (`lastStatedBalance` — the last element of `rows`, in table/file
order, with a non-null balance; **not** anything derived through `BalanceChainUtil`) against the
`closingBalance` it was handed. If they agree, it blames the opening balance; if they don't, it
blames the transactions (`suspectedCause: TRANSACTIONS`).

On this document, `lastStatedBalance` reads the value off the true last row (**B'**, the debit,
table-order-last) — correctly, since `StagedRow` list order here genuinely does match chronological
order (unlike the PNB statement `BalanceChainUtil`'s own doc comment cites, which lists
newest-first). `closingBalance`, meanwhile, is `BalanceChainUtil.last()`'s wrong pick (**A**, the
credit). The two disagree — not because a transaction is actually wrong, but because one of the two
values being compared is a bug's output and the other is the correct answer. The validator has no
way to know that, so it reports `suspectedCause: TRANSACTIONS`: **the misdiagnosis actively points
whoever reads it at the wrong subsystem.** The transactions are fine; the derived closing balance is
not.

Worth noting for the eventual fix: `lastStatedBalance`'s plain table-order read happens to be
reliable here precisely because this document's row order matches chronological order — the same
assumption `BalanceChainUtil` was built to not depend on, since it is provably false on at least one
real document already in the corpus (PNB ONE). This is not evidence that trusting file order is safe
in general — it isn't — only that on this specific document, the simpler signal accidentally landed
on the truth while the more careful one didn't.

## Also observed, still unexplained, out of scope here

The committed regression trace for this document family (`icici-savings-ledger-validation.trace`)
shows zero staged transactions at baseline, while the real, unredacted PDF — run through the
identical current codebase — correctly stages all 11. This trace-vs-real-file divergence was
observed during this investigation but not chased down; it is a separate open question, not
addressed by anything above.

## Recommendation

Close 2E.3 as **not reproduced** against real evidence — no fix needed for narration. Track the
`BalanceChainUtil` same-day-reversal defect as a 2G item (transaction reconciliation), since it is
literally inside the validator/utility layer 2G's own plan entry names as the likely evidence
foundation. Recommend a real-corpus sweep for other same-day closed-loop clusters before designing
the fix, so the anchor-balance design (if that's the chosen direction) is validated against more
than the one instance that surfaced it here.
