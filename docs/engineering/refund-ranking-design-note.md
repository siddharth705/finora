# Design Note — Which purchase does a refund attach to?

**Status: decision requested. Nothing implemented, and nothing here should be implemented until
the question below is answered.**

This concerns `ReconciliationService`'s refund pass and `isCloserRefundMatch`. It is a
money-classification rule, so it is written up rather than changed.

---

## How it was found

`ReconciliationEndToEndTest` was added to exercise all three reconciliation passes on one realistic
month. Its first run failed — not because the code was broken, but because the fixture assumed a
behaviour the code does not have. That is the finding.

The fixture was one month on a credit card:

| # | date | description | merchant | amount | type |
|---|---|---|---|---|---|
| 1 | 05 Jul | MYNTRA RETAIL | Myntra | ₹3,200.00 | EXPENSE |
| 2 | 08 Jul | BIG BAZAAR | *(none)* | ₹2,145.50 | EXPENSE |
| 3 | 26 Jul | MYNTRA REFUND FOR ORDER | Myntra | ₹1,600.00 | INCOME |

The ₹1,600 Myntra refund attached to **the groceries**, not to the Myntra purchase.

---

## Why — the current rule

Two separate mechanisms, and the distinction is the whole point.

**Entry to the candidate set** (inside the loop) requires *at least one* of two signals:

```java
boolean sameMerchant = expense.getMerchant() != null && !expense.getMerchant().isBlank()
        && expense.getMerchant().equalsIgnoreCase(income.getMerchant());
if (!refundKeyword && !sameMerchant) continue;
```

`refundKeyword` is read from the **income's own description**, so it is the same value for every
candidate. A refund that says "REFUND" anywhere admits *every* same-account expense in the 180-day
window, regardless of merchant.

**Ranking among candidates** (`isCloserRefundMatch`) consults neither:

```java
boolean candidateExact = candidate.getAmount().compareTo(income.getAmount()) == 0;
boolean currentExact  = currentBest.getAmount().compareTo(income.getAmount()) == 0;
if (candidateExact != currentExact) return candidateExact;   // 1. exact amount wins

long candidateDays = ChronoUnit.DAYS.between(candidate.getTxnDate(), income.getTxnDate());
long currentDays   = ChronoUnit.DAYS.between(currentBest.getTxnDate(), income.getTxnDate());
return candidateDays < currentDays;                          // 2. otherwise, nearest date wins
```

Applied to the fixture: neither candidate is an exact ₹1,600 match, so it falls to date proximity —
groceries at **18 days** beats Myntra at **21 days**. Correct per the code, wrong per the world.

**So: merchant is a gate, never a tiebreak.** A confirmed merchant match on one candidate loses to
an unrelated expense that happens to be three days nearer.

---

## Why this is not obviously a bug

Worth stating, because the fix looks self-evident and is not.

1. **Amount is the stronger signal, and it already wins.** An exact-amount candidate beats a nearer
   one today. The failure needs *no* exact match among candidates — common for partial refunds,
   less so otherwise.
2. **Merchant resolution is itself a heuristic.** `MerchantNormalizationEngine` groups by first
   significant token; its own doc records that it "will miss less obvious cases," and the manual
   merge feature exists because it does. Promoting merchant above date proximity promotes one
   heuristic above another, and the wrong-merchant case is *silent* where a wrong-date case at
   least tends to be recent and noticeable.
3. **Nobody has reported it.** This came from a synthetic fixture, not a user.

---

## Proposed behaviour

Insert merchant between the two existing criteria, changing nothing else:

```
1. exact amount match          (unchanged, still first)
2. same merchant               (NEW)
3. nearest date                (unchanged, now last)
```

Rationale for that position, specifically:

- **Below exact amount**, because an exact-amount match on the same account within the window is
  the strongest evidence available, and merchant resolution is a heuristic that can be wrong.
- **Above date proximity**, because date proximity is not evidence of anything — it is a tiebreak
  standing in for evidence. Where a real signal exists, it should be preferred over an arbitrary
  one.

Deliberately **not** proposed: making merchant an entry requirement. That would stop matching
refunds where the merchant did not resolve on one side, which is exactly the case the keyword
signal exists to cover.

---

## Expected impact

**On the 21 existing `ReconciliationServiceTest` cases: none.** Each constructs the minimum data
for one rule, so no test presents two competing refund candidates — the situation this changes.
Verified by reading them, not assumed; the ranking only matters with ≥2 admitted candidates.

**On `ReconciliationEndToEndTest`: one fixture line becomes unnecessary.** Groceries was lowered to
₹1,245.50 so that "a refund cannot exceed the purchase" excludes it. Under the proposal it could go
back to ₹2,145.50 and the merchant tiebreak would carry it — a strictly better test, since it would
then exercise the ranking rather than dodge it.

**On real data: unknown, and that is the honest answer.** How often two candidates are admitted and
merchant disagrees with date proximity is not something this repository can currently answer.

**But it will be answerable.** Every refund match now stores `sameMerchant` and
`dateDifferenceDays` in `reconciliation_explanation` (V55). Once there is production data, the
affected population is a query:

```sql
SELECT count(*) FROM transactions
WHERE reconciliation_explanation->'reason'->>'sameMerchant' = 'false'
  AND reconciliation_explanation->'reason'->>'refundKeyword' = 'true';
```

That is the set where the match rests on a keyword alone and merchant did not agree — i.e. every
match this change could plausibly move.

---

## Recommendation

**Do not implement yet.** The change is small and the reasoning is sound, but it reclassifies money
on real accounts and the affected population is currently unmeasured — the same standard applied to
`reconcileForUser`, where measuring first changed what was worth doing.

Suggested order:

1. Let V55 collect explanations in production for a normal usage period.
2. Run the query above. If the population is zero, the change is unnecessary; if it is large, that
   is itself a finding about the keyword signal being too permissive.
3. Decide with that number in hand, and add an end-to-end case with two competing candidates
   whichever way it goes — the gap that let this sit unnoticed is that nothing tested competition.

The alternative — implementing now on the strength of one synthetic fixture — is exactly the
build-ahead-of-evidence pattern the rest of this work has avoided.
