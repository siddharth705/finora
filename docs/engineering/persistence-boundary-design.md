# Design note: letting a financial entity survive persistence without transactions

**Status:** design only. Nothing here is implemented, and nothing should be until the
observability milestone (document classification → corpus run → corpus diff → ground truth) is
finished. The reason is in §11.

**Principle:** *a detected financial section must be able to survive persistence even when it
contains zero transaction rows.*

---

## 1. The finding that motivates this

`Shivani_HDFC.pdf` is one statement containing three financial products. The pipeline locates all
three, and then:

| Section | Product in the document | rows | account number | typed as |
|---|---|---:|---|---|
| 0 | Savings | **75** | `••••4291` | SAVINGS |
| 1 | **Recurring Deposit** | **0** | `null` | SAVINGS / UNKNOWN |
| 2 | **Fixed Deposit** | **0** | `null` | SAVINGS / UNKNOWN |

The document plainly contains both deposits. The evidence is the bank's own vocabulary, which is
what matters here and is not customer data: an `INR RECURRING DEPOSITS` narration carrying an amount
and a `CR` marker, an `FD DETAILS` heading, and `FD Number` / `Principal` / `Maturity Amount` labels.
The RD narration also carries its account number in full, which is how we know a real identity is
available to the parser. Two of three products are silently dropped, and the import reports success.

**Deliberately not reproduced above:** that account number, or any amount from the statement. An
earlier revision of this note quoted both. They are one real person's banking details, this
repository is where they must never be, and a design note is not exempt from that rule because it is
prose rather than a fixture. See §14.

## 2. Extraction is not the problem

`DetectedAccountInfo` is already a financial-entity model, per section: identity
(`accountNumberMasked`, `accountHolderName`, `branchName`, `ifscCode`, `bank`), statement facts
(`openingBalance`, `closingBalance`, `statementPeriodStart/End`), card facts (`creditLimit`,
`paymentDueDate`), deposit facts (`principalAmount`, `interestRate`, `maturityDate`,
`maturityAmount`, `installmentAmount`, `installmentsPaid`, `installmentsTotal`) and inference with
provenance (`detectedProduct`, `productConfidence`, `productEvidence`, `productNeedsReview`,
`productIdentityHash`).

`ProductAttributeExtractor` already searches for the deposit vocabulary: `"principal amount"`,
`"maturity date"`, `"maturity amount"`, `"maturity value"`, `"interest rate"`,
`"monthly installment"`, `"installment paid"`, `"deposit amount"`.

The four-way distinction — observed, derived, inferred, unknown — is already how this model behaves:
absent values are `null` rather than guessed, inference carries `productConfidence` and
`productEvidence`, and `productNeedsReview` exists to ask the user instead of inventing an answer.
**No new hierarchy is needed to express any of it.**

## 3. Where information is lost

All at or after the commit boundary, never during parsing:

| Information | Lost where | Consequence |
|---|---|---|
| `productEvidence` | Not persisted by anything; no main-source consumer | The reasoning behind every product inference is unauditable after import |
| `openingBalance`, `closingBalance` | On no entity | "What did the statement say the closing balance was" is unanswerable later |
| `statementPeriodStart/End` | On no entity | Same; and re-import cannot reason about period overlap |
| Section-level verification findings | `ImportVerificationFinding` is keyed per import/session, not per section | A composite statement's per-section failure collapses to one document verdict |
| **A section with zero transactions** | The commit path is transaction-centric | RD and FD above |

## 4. Proposed change, and it is one inversion

Today a section is the **carrier** for transactions: no transactions, nothing to carry, section
gone. Proposed: a section is a **financial entity that may have transactions**.

```
   now:  section -> transactions -> Account (created because transactions needed a home)
   next: section -> financial entity -> Account (created from identity) -> transactions attach if present
```

That is the whole change. It is a decision in the commit path, not a new type system.

## 5. What existing tables already support this (question 1)

More than expected. `Account` already declares `productType`, `investmentKind`,
`productIdentityHash`, `principalAmount`, `interestRate`, `maturityDate`, `maturityAmount`,
`installmentAmount`, `installmentsPaid`, `installmentsTotal`, `creditLimit`, `dueDate`,
`accountNumberMasked`, `accountHolderName`, `branchName`, `ifscCode`.

**The FD/RD schema is already in the database.** A "Fixed Deposit — principal, rate, maturity
amount, maturity date" screen has columns waiting for it. Routing exists too: `FinancialProductType`
sends a term deposit to `INVESTMENT` with `investmentKind` `FD`, landing it in the Investments module
rather than as an empty account.

## 6. What identity actually is for an RD or FD (the question worth asking)

**Already solved, and solved for exactly this reason.** `ProductIdentity` is
`(institutionId, type, strongKey, maskedNumber)` where `strongKey` is a one-way hash of institution +
the product's own full number + a discriminator. And:

```java
ProductIdentity.forDeposit(principalAmount, maturityDate, installmentAmount)
```

A deposit's discriminator is its **principal, maturity date and installment amount** — precisely
because several deposits can share one account number. Its own comment records the incident that
caused it ("section hashed to the same key, and the consequence was not a cosmetic duplicate"), and
it deliberately falls back to number-only identity when all three are null rather than hashing three
nulls into a collision.

So `productIdentityHash` + `Account` **is sufficient**. Do not build a parallel identity model.

**But note the dependency this creates.** A deposit's identity is derived from principal, maturity
date and installment amount — the very fields that came back `null` for Shivani's RD and FD. So
persisting those sections is not blocked by the identity model; it is blocked by attribute extraction
returning nothing for them. Fixing persistence without fixing extraction yields two entities with no
identity, which `forDeposit` will correctly refuse to distinguish. **These two must land together.**

## 7. New persistence actually required (question 2)

Little:

- `statement_imports`: `opening_balance`, `closing_balance`, `statement_period_start`,
  `statement_period_end`
- Somewhere for `productEvidence` (see §8)
- A per-section discriminator on verification findings (see §9)

No new table for the entity itself. `Account` covers it.

## 8. Where the balances, period and evidence belong (questions 3, 4)

**Opening/closing balance and statement period belong on `statement_imports`, not `Account`.** They
are facts about *one document*, not about the account: two statements for the same account have
different periods and different closing balances. Putting them on `Account` would mean the second
import overwrites the first, and the answer to "what did the March statement say" is lost.

**`productEvidence` belongs beside the verification findings, not on `Account`.** It is provenance
for a decision made during one import — same lifetime, same audit purpose as a verification finding,
and the same reason for existing. `ImportVerificationFinding` already carries a `detailsJson`, which
is the natural shape. On `Account` it would be overwritten by every subsequent import, destroying the
audit trail it exists to provide.

## 9. Retaining section-level verification (question 5)

`ImportVerificationFinding` is keyed to an import or an analysis session, with no section
discriminator, so a three-section statement collapses into one set of findings. The minimum is a
nullable `section_index` column. The corpus work has already shown why: collapsing sections hid a
`COLUMN_AMBIGUITY` warning on Shivani's second section behind a later section's `VERIFIED`.

## 10. Re-import, duplicates, and the UI distinction (questions 6, 7, 8)

**Re-import of a zero-transaction product** resolves through `ProductIdentityResolver` exactly as a
transactional one does — `mayImportWithoutAsking()` already gates the ambiguous case, and its comment
already states the preference: a visible duplicate the user can merge beats quietly importing into
the wrong deposit. Nothing new required. What *is* new: re-importing the same FD should update its
attributes (a maturity amount can accrue) rather than create a second entity, so the merge path needs
a defined update rule.

**Duplicate detection for RD/FD is not transaction duplicate detection.** `DuplicateDetector` compares
transactions; a deposit with no transactions never reaches it. Deduplication for these entities is
`ProductIdentity` alone, which is the correct mechanism and already exists.

**The UI distinction** — "this product exists" versus "we have its transaction history" — is
expressible with what would then be persisted: an entity with attributes and zero transactions. The
FD card shows principal, rate, maturity; the transaction list is legitimately empty and should say
*"no transaction history in the imported statement"* rather than rendering as an error or an empty
account. This is the difference between **unknown** and **absent**, and conflating them is the same
class of mistake as turning a missing value into a guess.

## 11. Smallest migration, and what must not change (questions 9, 10)

**Migration:** four columns on `statement_imports`, one nullable `section_index` on verification
findings, and a JSON column (or reuse of `detailsJson`) for evidence. No new tables, no changes to
`transactions` or `accounts`.

**Must remain unchanged:**

- The transaction path end to end. Every currently-importing statement must produce byte-identical
  accounts and transactions.
- The four validators, `PdfTextExtractor`, `PdfTableLocator`, the capability registry, layout
  fingerprinting.
- `ProductIdentity` and `ProductIdentityResolver` semantics, including `mayImportWithoutAsking()`.
- The "ask, don't guess" posture: `productNeedsReview` must keep meaning *ask the user*.

## 12. Why this waits for the observability milestone

The success condition for this change is "RD and FD survive import **and Savings is unchanged**".
Today nothing can verify the second half. Shivani's record currently reads
`sections: 3, rows: 75, COLUMNS_AMBIGUOUS` — one statement that looks broadly fine — and would read
the same if a change to the commit boundary quietly altered how the Savings section's 75 rows were
attributed.

Per-section corpus records plus a diff make that verifiable. Ground truth makes `[75, 0, 0]` a defect
rather than a baseline. Both are prerequisites, not neighbours — and this note exists so the target is
agreed before the instrument to hit it safely is finished.

## 13. What this note deliberately does not propose

No `FinancialDocument` / `FinancialProduct` hierarchy. `DetectedAccountInfo` per section already is
the financial-entity model, `Account` already stores it, and `ProductIdentity` already identifies it.
A parallel type system would mean two models to keep in step while neither is complete — and the
evidence above is that the existing one is closer to the goal than a new one would be for months.

## 14. Why no statement value appears in this note

The corpus is kept outside the working tree so real statements cannot be committed. That control
protects *files*. It does not protect against a value being retyped into prose, which is what an
earlier revision of §1 did with a full account number and a transaction amount.

Two consequences for the ground-truth work that follows:

- **Ground truth for the real corpus must live outside the repository too.** It is derived from these
  documents and describes the same facts at the same sensitivity -- expected principals, maturity
  amounts and identities are customer financial data whether they sit in a PDF or in a YAML file
  beside it. Only *synthetic* ground-truth fixtures belong in the tree, and their job is to test the
  mechanism, exactly as `scripts/test-corpus-diff.py` tests the diff without the corpus.
- **Reasoning about a document is allowed; reproducing its values is not.** A bank's own vocabulary
  (`FD DETAILS`, `Maturity Amount`, an `INR RECURRING DEPOSITS` narration) is generic to the format
  and is what the argument actually rests on. The customer's number and amount added nothing to it.
