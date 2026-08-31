# Reversible ABSOLUTE-Mode Balance Contributions — Design Spec

**Date:** 2026-08-30
**Status:** Approved for implementation planning
**Files under change:** `backend/src/main/java/com/finora/entity/StatementImport.java`, `backend/src/main/java/com/finora/entity/Account.java`, `backend/src/main/java/com/finora/imports/ImportService.java`, `backend/src/main/java/com/finora/accounts/AccountService.java`, `backend/src/main/java/com/finora/service/StatementImportService.java`, plus a new Flyway migration.

## Problem

[PR #638](https://github.com/finora/finora/pull/638) (branch `Finora/friendly-diffie-a6833d`, open, not yet merged) found that `StatementImportService.supersede()` skipped balance reversal for an `ABSOLUTE`-mode original on the assumption that the replacement's own confirm already overwrote the balance. That only holds when the replacement's own confirm *also* lands `ABSOLUTE` — otherwise the replacement's confirm just adds its net delta on top of the balance the original had already set, silently double-counting the original's contribution. PR #638 ships a guard clause in `supersede()` (right after the existing "already superseded" checks) that refuses the call outright in the mismatched-mode case, as a deliberately cheap stopgap pending a real fix.

A follow-up corpus investigation (`ClosingBalanceCorpusProbe`, commit `a5069265`) measured how often the refusal actually fires against the real 27-file/32-section local corpus:

- **0/10 credit-card sections ever reach `ABSOLUTE` mode** — no CC statement has a detected closing balance; it's a running-balance-column concept, and CC statements are itemized bills.
- **CSV imports never populate a statement-level closing balance** — `CsvParser` only tracks a per-row running-balance column.
- **~20% of non-degenerate real savings-account PDFs** (3 of ~15) never had a closing balance detected, despite extracting real rows.

So the PR #638 refusal isn't a narrow edge case — it's the default outcome for every credit card, every CSV import, and a meaningful slice of savings statements. This spec is the "Option 2" design deferred at PR #638 time: make the reversal actually work, so "Import as a replacement" isn't hard-blocked for the majority of real statement shapes.

## Why the obvious approaches don't work

**Recompute the reversal from the statement's own rows at supersede time?** Unsafe — `StatementImport.BalanceApplicationMode`'s own doc comment already establishes this: `totalCredits`/`totalDebits` were never persisted, and `Transaction.amount` is editable after import, so re-deriving anything from current row state doesn't reproduce what the original confirm actually did.

**Reconstruct "the balance before the original's SET" from `effectiveOpeningBalance` (`opening + net == closing`, the corroboration identity `ClosingBalanceGuard` already checked at confirm time)?** Also unsafe. [`OpeningBalanceCarryForward.resolve`](../../../backend/src/main/java/com/finora/imports/OpeningBalanceCarryForward.java) shows `effectiveOpeningBalance` comes from the **account's prior statement's stated closing balance**, not from live `Account.balance` at confirm time. If the user manually edited the balance, or posted a manual transaction, between that prior statement and the original's own confirm, `effectiveOpeningBalance` and live `Account.balance` diverge — the arithmetic identity no longer reconstructs what the live balance actually was.

This also means `StatementImportService.delete()`'s current reversal (negate the row-level net delta via `AccountBalanceConvention.netDelta(...).negate()`, applied uniformly regardless of `BalanceApplicationMode` — see lines 379-384) is only *coincidentally* correct for `ABSOLUTE`-mode rows, and is provably wrong whenever `effectiveOpeningBalance` diverged from live balance at that statement's own confirm time. This is the same reversal bug PR #638 fixed for `supersede()`, present in `delete()` too, not yet fixed there.

**Conclusion:** the only safe source for "what the balance was before an ABSOLUTE SET" is reading `Account.balance` live, at the instant of the SET, and persisting that snapshot. Nothing can be reconstructed after the fact — same principle `BalanceApplicationMode` itself already established ("recorded once ... rather than reconstructed").

## The invariant

A SET (`account.balance = statedClosingBalance`) is only safely reversible if nothing *else* has SET the balance since. Ordinary additive operations (transactions, `ADDITIVE`-mode statement confirms, the reversal itself) always commute — they can be stripped out or composed regardless of order, which is why the existing `ADDITIVE`-mode reversal (recompute live net over currently-still-contributing rows) is already correct regardless of what else happened around it. A SET breaks that commutativity: reversing one requires knowing whether it's still the account's live anchor, or whether a later SET has already fully overwritten it.

**Target outcome**, stated explicitly (confirmed with Sid 2026-08-30): after `supersede(original → replacement)`, `Account.balance` should equal what it would be if `original` had never confirmed at all, but `replacement`'s own confirm had applied on top of whatever balance existed before `original`. When `replacement` itself lands `ABSOLUTE` (its own stated closing balance corroborates against its own rows), that already means "replacement's own confirm set the balance directly" — no separate reversal math needed. When `replacement` lands `ADDITIVE` or `NONE`, the balance is derived from `replacement`'s actual transaction rows only, **never** from a stated-but-uncorroborated closing balance on `replacement` — same invariant `ClosingBalanceGuard` already enforces on first import (an unreconciled figure is never applied to `Account.balance`, whether at confirm or at supersede). Introducing a special case that trusts an uncorroborated number specifically at supersede time would reopen the exact hole `ClosingBalanceGuard` exists to close. A future explicit "force account balance to statement closing balance despite mismatch" user action is a plausible separate workflow, but out of scope here — it would need to be an explicit, distinguishable user decision, not an automatic side effect of supersession.

## Design

### Data model (new Flyway migration; version number TBD at implementation time — check `backend/src/main/resources/db/migration` against `origin/main` first, per this repo's migration-collision discipline)

- `StatementImport.balanceBeforeAbsoluteSet` (new nullable `BigDecimal`, column `balance_before_absolute_set`) — `Account.balance` read live, the instant before `persistSection`'s `ABSOLUTE` branch overwrites it. Populated only when `balanceApplicationMode` is being set to `ABSOLUTE`; null for every other row, including every row confirmed before this migration ships (same "never guess, never backfill" stance `UNKNOWN_LEGACY` already takes for `BalanceApplicationMode` itself).
- `Account.lastAbsoluteSetStatementId` (new nullable `UUID`, column `last_absolute_set_statement_id`) — which `StatementImport` most recently SET (not added to) this account's balance. Null means "no statement currently owns the live anchor" — either nothing has ever SET it, or a manual edit invalidated the claim.

### Write-path changes

1. **`ImportService.persistSection`** ([ImportService.java:1187-1193](../../../backend/src/main/java/com/finora/imports/ImportService.java)), `closingBalanceIsAuthoritative` branch: capture `BigDecimal priorBalance = account.getBalance();` before the overwrite. Set `savedImport.setBalanceBeforeAbsoluteSet(priorBalance)` alongside the existing `balanceApplicationMode` write (~line 1247, same dirty-checked pattern already documented there — no extra `save()` call). Set `account.setLastAbsoluteSetStatementId(savedImport.getId())` in the same block that calls `account.setBalance(...)`.
2. **`AccountService.update`** ([AccountService.java:146](../../../backend/src/main/java/com/finora/accounts/AccountService.java)): when `req.balance() != null` (a manual balance edit), also `a.setLastAbsoluteSetStatementId(null)` — a manual SET invalidates any statement's claim to being the live anchor, for the same reason a later ABSOLUTE-mode statement would.

### Reversal primitive (new private helper in `StatementImportService`, shared by `supersede()` and `delete()`)

```java
private boolean reverseAbsoluteContribution(StatementImport original, Account account) {
    if (original.getBalanceBeforeAbsoluteSet() == null) {
        // Predates this fix -- BalanceApplicationMode says ABSOLUTE, but we never captured what
        // the balance was before the SET. Guessing risks the exact corruption this exists to
        // prevent; same stance as UNKNOWN_LEGACY.
        log.warn("Cannot reverse ABSOLUTE contribution for statement {}: no pre-SET snapshot " +
                "(row predates this fix). Balance not adjusted; verify manually if needed.",
                original.getId());
        return false;
    }
    if (!original.getId().equals(account.getLastAbsoluteSetStatementId())) {
        // Something else (a later-period ABSOLUTE statement, or a manual account edit) has SET
        // the balance since -- original's contribution is already fully overwritten. Correct to
        // do nothing, not a gap.
        return false;
    }
    BigDecimal delta = original.getBalanceBeforeAbsoluteSet().subtract(original.getClosingBalance());
    if (delta.signum() != 0) {
        account.setBalance(account.getBalance().add(delta));
    }
    account.setLastAbsoluteSetStatementId(null);
    accountRepository.save(account);
    return true;
}
```

### `supersede()` changes

- **Delete the PR #638 guard clause entirely** (the `if (original.getBalanceApplicationMode() == ABSOLUTE && replacement.getBalanceApplicationMode() != ABSOLUTE) throw ...` block, and its accompanying doc-comment paragraph).
- In the `BalanceApplicationMode` switch, change the `ABSOLUTE` case from a no-op to calling `reverseAbsoluteContribution(original, account)`:
  - If `replacement` was itself `ABSOLUTE`: `account.lastAbsoluteSetStatementId` already points to `replacement` by the time `supersede()` runs (set during `replacement`'s own confirm) — the helper's pointer check naturally no-ops. Same outcome as today's `ABSOLUTE` case, now arrived at correctly instead of by assumption.
  - If `replacement` was `ADDITIVE`/`NONE`: the pointer still points at `original` (replacement's own confirm never touched it) — the helper reverses correctly, leaving `Account.balance` at `original`'s pre-SET baseline plus `replacement`'s already-applied net delta.
  - If some other statement (a later period, `ABSOLUTE`) or a manual edit intervened: pointer points elsewhere/null — helper correctly no-ops, `original`'s contribution was already gone.
- `NONE` and `UNKNOWN_LEGACY` cases are untouched.
- `SupersedeResult.balanceReversed` reflects the helper's return value for the `ABSOLUTE` case, same as it already does for `ADDITIVE`.

### `delete()` changes

- Branch on `statementImport.getBalanceApplicationMode()`:
  - `ABSOLUTE`: call `reverseAbsoluteContribution(statementImport, account)` instead of today's row-`netDelta`-based reversal.
  - `ADDITIVE` / `NONE` / `UNKNOWN_LEGACY`: unchanged — today's row-based reversal is already correct for these (their contribution genuinely *is* the rows' live net effect, unlike `ABSOLUTE`'s SET semantics).
- `delete()` stays `void`. The legacy-row and moot cases inside the shared helper already `log.warn` server-side, consistent with how `persistSection`'s own `UNCORROBORATED` case is handled (logged, not surfaced synchronously) — avoids a response-contract change that would ripple into `StatementImportController` (204 response) and `AccountPurgeSweepService` (unattended sweep, nothing to show a warning to).

## Worked examples

**Case A — replacement has no closing balance concept (credit card / CSV), the dominant real case.** Original: opening 100, corroborated closing 1000 → `ABSOLUTE`, `balanceBeforeAbsoluteSet = 100`. Replacement (CSV, same period): its own rows net +700, no stated closing balance → `ADDITIVE`. At replacement's own confirm, balance moves 1000 → 1700 (net delta applied on top of original's SET). At `supersede()`: pointer still points at original → reversal `delta = 100 − 1000 = −900` → balance `1700 + (−900) = 800`, i.e. `100 + 700` — original's baseline plus replacement's real net, exactly the target invariant.

**Case B — replacement states a closing balance that fails to reconcile with its own rows.** Same original. Replacement states closing = 5000, but its own rows only net +700 (implying 1700, not 5000) → fails `ClosingBalanceGuard`, lands `ADDITIVE`. Reversal proceeds exactly as Case A (5000 is never trusted) — result is 800, not 5000. This is the invariant confirmed with Sid: an unreconciled stated figure is never authoritative, at supersede time or at confirm time.

**Case C — replacement itself corroborates.** Replacement states closing = 1200, and its own rows net +1100 with opening 100 → corroborates → `ABSOLUTE`. Replacement's own confirm sets balance = 1200 directly and moves `lastAbsoluteSetStatementId` to replacement. At `supersede()`, the helper's pointer check no-ops (`original.getId() != replacement's id`) — balance stays 1200, unchanged. Matches "new statement becomes authoritative" for the case where it actually earned that trust.

**Case D — manual edit intervenes.** Original confirms `ABSOLUTE` (balance 1000, snapshot 100). Before superseding, the user manually edits the account balance via `AccountService.update` to, say, 2000 — this clears `lastAbsoluteSetStatementId` to null. Replacement confirms `ADDITIVE`, net +700 → balance 2700. At `supersede()`: pointer is null, doesn't match original's id → no-op, balance stays 2700 (a `log.warn` records that no automatic reversal was possible). Correct: the manual edit already invalidated any claim original's SET had on the current balance's lineage.

**This is a deliberate product decision, not just a technical consequence, and is stated explicitly here so it isn't mistaken for an oversight later:** once a manual balance edit occurs, automatic balance lineage is intentionally abandoned. Finora does not attempt to track "how much of the current balance is still attributable to a statement that predates a manual edit" — the manual edit is treated as a fresh, fully-trusted baseline, same as if the account were created fresh at that value. The alternative (trying to preserve lineage *through* a manual edit) would require guessing how the user's manual figure relates to the statement history it interrupted, which is exactly the kind of reconstruction this design otherwise refuses to do. If a later statement needs correcting after a manual edit sits between it and now, the safe outcome is "no automatic reversal, verify by hand" — the same conservative default already used for legacy rows and moot cases.

## Write-path audit: every mutation of `Account.balance`

The pointer invariant only holds if every writer of `Account.balance` either (a) is additive and leaves the pointer alone, or (b) is a genuine SET and updates the pointer accordingly. Searched the full backend (`grep -rn "\.setBalance(" backend/src/main/java`) plus the one known, not-yet-merged sibling change (PR #633 / commit `be78050e`, branch history this worktree doesn't yet include). Every write site found:

| Site | Shape | Pointer treatment |
|---|---|---|
| `AccountService.create` (`AccountService.java:105`) | SET (initial value) | None needed — new account, `lastAbsoluteSetStatementId` starts null, nothing to invalidate. |
| `AccountService.update` (`AccountService.java:146`), when `req.balance() != null` | SET (manual edit) | **Changed by this design** — clears the pointer to null (see Case D and the product-decision note above). |
| `ImportService.persistSection`, `closingBalanceIsAuthoritative` branch (`ImportService.java:1191`) | SET (statement closing balance) | **Changed by this design** — sets pointer to the confirming statement's id; also captures `balanceBeforeAbsoluteSet`. |
| `ImportService.persistSection`, else branch (`ImportService.java:1203`) | ADD (net of inserted rows) | None — additive, commutes, this is the `ADDITIVE` path itself. |
| `ImportService.persistSection`, BH-003 duplicate reversal (`ImportService.java:1347`) | ADD (negated duplicate net) | None — explicitly gated `!closingBalanceIsAuthoritative` in the existing code, i.e. already scoped away from `ABSOLUTE` rows. |
| `TransactionService.adjustAccountBalance` (`TransactionService.java:167`) — the shared helper behind manual transaction create/update/delete | ADD (delta) | None — always additive by construction. |
| `TransactionService.confirmNotDuplicate` (PR #633 / `be78050e`, not yet in this worktree's branch) | ADD, via `adjustAccountBalance` | None — already gated to `BalanceApplicationMode.ADDITIVE` statements only in that PR's own diff (comment: "ABSOLUTE/NONE never moved the balance via this row's net effect"). Re-verify this gate is still present at merge/rebase time, since this design and PR #633 are sibling in-flight changes to related code. |
| `StatementImportService.delete`, existing reversal (`StatementImportService.java:382`) | ADD (negated net of still-contributing rows) | **Changed by this design** — this call site now only handles `ADDITIVE`/`NONE`/`UNKNOWN_LEGACY`; `ABSOLUTE` rows route through the new `reverseAbsoluteContribution` instead. |
| `StatementImportService.supersede`, existing `ADDITIVE` case (`StatementImportService.java:491`) | ADD (negated net of still-contributing rows) | None — unchanged, already correct, doesn't touch the pointer. |
| `reverseAbsoluteContribution` (new, this design) | ADD (restores pre-SET baseline) | **New site** — clears the pointer to null after a successful reversal (original's SET is fully undone; nothing currently owns the anchor). |

No write path was found that sets `Account.balance` outside these two services (`AccountService`, `ImportService`) plus the reversal logic in `TransactionService`/`StatementImportService` — no separate admin tool, background sweep, or migration script writes this column directly via JPA. `AccountPurgeSweepService` only calls `StatementImportService.delete()` (already audited above), never touches `Account.balance` itself. This should be re-verified with a fresh grep immediately before implementation, since new write paths could be added between now and then.

## Testing plan

1. Extend or replace `SupersedeRefusesMismatchedAbsoluteModeIT` with a version proving the *correct reversal* (Case A/B numbers above) instead of asserting a 400.
2. New integration test for Case C (replacement also `ABSOLUTE`) — assert the pointer-based no-op path, `balanceReversed == false`, final balance equals replacement's own stated figure.
3. New integration test for Case D (intervening manual `AccountService.update` balance edit between original's confirm and `supersede()`) — proves the pointer-clear path.
4. New integration test for an intervening later-period `ABSOLUTE` statement on the same account between original's confirm and `supersede()` of an earlier-period original — proves the moot/no-op path is correct, not a regression.
5. `delete()` regression test where `effectiveOpeningBalance` diverges from live balance at the `ABSOLUTE` statement's own confirm time (opening carried forward from a prior statement, but a manual transaction posted in between) — demonstrates the old row-`netDelta` reversal was wrong and the new snapshot-based one isn't.
6. Legacy-row test: an `ABSOLUTE`-mode `StatementImport` with `balanceBeforeAbsoluteSet == null` (simulating a pre-migration row) going through both `supersede()` and `delete()` — assert no balance change and a warning is logged, not a corrupted balance.
7. Unit tests on `reverseAbsoluteContribution` directly, covering all four branches (no snapshot / pointer mismatch / pointer match with non-zero delta / pointer match with zero delta).

## Out of scope

- Any change to `ClosingBalanceGuard`'s corroboration arithmetic itself.
- An explicit "force account balance to statement closing balance despite mismatch" user-facing override workflow — plausible future feature, deliberately not folded into automatic supersession behavior (see Invariant section).
- Backfilling `balanceBeforeAbsoluteSet` or `lastAbsoluteSetStatementId` for any pre-migration row — not reconstructible, same reasoning `UNKNOWN_LEGACY` already established for `BalanceApplicationMode` itself.
- Any change to `ADDITIVE`/`NONE`/`UNKNOWN_LEGACY` handling in either `supersede()` or `delete()` — both already correct or already deliberately conservative for those modes.
