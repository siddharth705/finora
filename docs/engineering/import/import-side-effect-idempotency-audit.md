# Import side-effect idempotency audit

**Question asked:** idempotent import ≠ idempotent workflow. Now that a replayed job cannot create a
duplicate `StatementImport` or duplicate transaction rows, what *else* happens during a confirm, and
which of those things tolerate being done twice?

**Answer: seven of nine are replay-safe on their own. Two are not — and both corrupt a financial
figure rather than merely wasting work. Today both are held safe by something else: confirm is a
single transaction, so a replay is rejected by V67 and rolls the whole thing back. That protection
is exactly what an execution model with checkpoints would remove.**

This is an audit, not a change. No behaviour was modified. It exists because checkpoint boundaries in
the forthcoming execution model belong exactly where side effects are *not* idempotent, and placing
them before knowing that would be guessing.

**Scope note, corrected after review:** none of these are live defects, and the reason is stronger
than "the worker does not confirm yet". `ImportService.confirm()` is `@Transactional`, and inside it
the `StatementImport` insert (line 644) happens *before* the learning enqueue (664) and the
transaction inserts (668). A replay therefore violates V67's `UNIQUE(import_job_id)` and **the entire
transaction rolls back** — no duplicate learning event, no second balance delta.

**These two become real defects only when the execution model splits confirm into separately
committed steps.** That is precisely what checkpointing does. So this audit does not describe bugs to
fix now; it describes *constraints the execution model must not violate*, which is a more useful
result and a stricter one — the risk is created by the design, not inherited by it.

---

## Summary

| # | Side effect | Replay-safe? | Mechanism |
|---|---|---|---|
| 1 | `StatementImport` creation | ✅ | `UNIQUE(import_job_id)` — V67 |
| 2 | Transaction inserts | ✅ | `UNIQUE(statement_import_id, row_ordinal)` — V67 |
| 3 | Merchant + alias creation | ✅ | `UNIQUE(user_id, normalized_alias)` — V7 |
| 4 | Account balance — **closing-balance branch** | ✅ | Absolute assignment |
| 5 | Account balance — **net-delta branch** | ❌ alone · ✅ today | **Relative mutation**, held by confirm's atomicity |
| 6 | Merchant learning enqueue → `confirmation_count` | ❌ alone · ✅ today | **Counter increment**, held by confirm's atomicity |
| 7 | Reconciliation pass | ✅ | Absolute recompute |
| 8 | Recurring detection pass | ✅ | Absolute recompute |
| 9 | Audit rows | ⚠️ | Duplicated, but no corruption |

---

## The two that fail on their own

### 5. Account balance, net-delta branch

`ImportService` has two balance paths, and only one is safe:

```java
if (closingBalanceIsAuthoritative) {
    account.setBalance(request.statementClosingBalance());   // absolute — safe
} else {
    BigDecimal net = AccountBalanceConvention.netDelta(account.getAccountType(), toInsert);
    account.setBalance(account.getBalance().add(net));       // relative — NOT safe
}
```

**Why it matters.** A replay applies `net` a second time. The account balance is then wrong by
exactly the value of one statement, permanently, with no error anywhere. The user sees a balance
that does not match their bank and has no way to explain it.

**Why the branch exists.** The absolute path is only taken when the statement's stated closing
balance is corroborated by `ClosingBalanceGuard`. When it is not — a partial statement, a missing
closing figure — the delta is the only available answer. So this cannot simply be replaced with the
absolute form.

**Options, in preference order:**

1. **Checkpoint before it.** Record in the job that balance has been applied; on resume, skip. Cheap,
   fits the execution model, and does not change the balance logic at all.
2. **Derive rather than accumulate.** Compute the balance from the transaction ledger instead of
   mutating a running total. Correct by construction and idempotent for free, but it is a change to
   how balances work everywhere, not an import fix.
3. Store the applied delta on the `StatementImport` and make reapplication detectable.

Option 1 is what the execution model should do. Option 2 is the better long-term answer and belongs
in its own discussion.

### 6. Merchant learning → `confirmation_count`

`MerchantLearningEventPublisher.enqueue()` saves a **new event row on every call** — there is no
dedup key. A replayed confirm enqueues the same learning again, and the worker applies both.

**The design document claims this is already handled:** *"The learning worker already does [tolerate
replay], via its unique constraint."* **That claim does not hold.** There is a unique constraint —
`UNIQUE(user_id, merchant_id, category_id)` on `merchant_category_learning`, V7 — but it guarantees
one learning *row*, not an idempotent *count*. The row is found and its counter incremented.

The codebase already documents the consequence, in `MerchantLearningEventRepository`:

> two workers select the same row, both apply the learning, and the merchant's `confirmation_count`
> increments twice. That is not wasted work — confirmation counts are what `ConfidenceEngine
> .topCategory` uses to decide which category is auto-applied, so double-processing silently changes
> the answer the engine gives.

**`SKIP LOCKED` protects against *concurrent* double-processing. It does nothing about *sequential*
replay**, which is exactly the import-retry case: the duplicate event is enqueued minutes later, and
the worker processes it normally.

**The consequence is subtler than a wrong balance and worse to diagnose.** A skewed confirmation
count changes which category is auto-applied to *future* transactions for that merchant. The user
sees miscategorised transactions with no connection to an import that succeeded weeks earlier.

### What a "confirmation" actually means — and why the obvious fix is wrong

The tempting constraint is `UNIQUE(source_statement_import_id, merchant_id, category_id)`, by
analogy with V67. **It would be a bug.**

`pendingLearning.add(...)` sits inside the per-row loop with no dedup, so a statement containing four
Amazon rows the user corrected to Shopping enqueues four events and adds four to the count. That is
not an accident. `docs/financial-intelligence-engine-spec.md` defines the semantics:

> `confirmation_count` is the real evidence; `confidence` is that row's cached % share of the
> merchant's **total confirmations**

The count is an **evidence weight**, not a press-count — and confidence is a *share* across the
categories for that merchant. So a user who corrects four Amazon rows to Shopping and one to
Groceries in the same statement means Shopping four times as strongly. A per-statement key would
record 1 and 1, erasing the signal the distribution exists to capture. The example in that spec
shows counts of 147, which only makes sense per-row.

**So the unique key, if one is ever needed, must distinguish rows rather than statements** — the
natural candidate being the transaction the confirmation came from, or `(statement_import_id,
row_ordinal)` mirroring V67.

**But per the corrected scope note above, no key is needed at all while confirm stays atomic.** The
right answer today is to preserve that atomicity, not to add a constraint whose semantics would be
wrong.

---

## The seven that hold, and why

Worth stating, because "probably fine" is not evidence.

- **Merchant and alias creation** — `UNIQUE(user_id, normalized_alias)` (V7). A replay finds the
  existing alias rather than creating a second.
- **Reconciliation** — assigns absolutely: `setIsDuplicateOf(id)`, `setReconciliationStatus(...)`,
  `setTransfer(true)`, `setTransferPairId(...)`. Re-running recomputes the same verdicts.
- **Recurring detection** — clears `recurring` on every active transaction and re-sets it from the
  detected groups. A full recompute is idempotent by construction.
- **Audit rows** — a replay writes a second `RECONCILIATION_RUN` / `IMPORT_*` row. Duplicated
  history is noise, not corruption, and arguably correct: the pass genuinely did run twice. Left
  alone deliberately.

---

## What this implies for the execution model

Checkpoints are only worth their complexity where a step is both **expensive** and **unsafe to
repeat**. This audit gives the actual boundaries:

```
DOWNLOADING   — safe to repeat (content-addressed read)
PARSING       — safe to repeat (pure)
STAGING       — safe to repeat (writes nothing since WI3)
CONFIRMING    — UNSAFE: balance delta (#5)          <- checkpoint here
LEARNING      — UNSAFE: confirmation count (#6)     <- checkpoint here
RECONCILING   — safe to repeat (absolute recompute)
```

**Only two checkpoints are actually required.** A design that checkpointed every stage would be
mostly ceremony; these two are load-bearing.

The resume rule follows from the same evidence: a job resuming after `CONFIRMING` must not reapply
the balance, and a job resuming after `LEARNING` must not re-enqueue learning — everything else can
simply re-run.

**The strongest option is to not split them at all.** Confirm is atomic today, and that atomicity is
what makes both unsafe operations safe. An execution model that keeps `CONFIRMING` and `LEARNING`
inside one transaction needs no checkpoint between them and no new constraint — it needs only a
checkpoint *before* the pair, recording that the pair has run. Splitting them buys finer-grained
resume and costs the guarantee that currently holds for free.

If they are split, both guards become mandatory rather than optional, and the learning one must key
on rows, not statements.

---

## Not covered

- **Notifications, webhooks, email, cache invalidation** were named as candidates. None exist in the
  import path today; there is nothing to audit. They will need this same treatment when added, and
  are the reason the execution model should make "has this step run?" a first-class question rather
  than a per-side-effect fix.
- **Ordinal stability across formats.** `row_ordinal` is stable for CSV, where row order is the
  file's order. For PDF extraction, and for a future API or Open Banking import, position may not be
  stable between runs — the same statement re-parsed could yield a different ordering, which would
  defeat constraint #2. A content-derived transaction fingerprint would not have that weakness. Not
  needed today; it becomes needed as ingestion formats expand.
