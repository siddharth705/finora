package com.finora.imports;

/**
 * What a parsed ledger row structurally IS, decided by {@link TransactionNormalizer#normalize}
 * from which column actually supplied its amount -- never from the row's description text.
 *
 * <p>{@link #TRANSACTION}: the amount came from a real debit/credit/amount-style column (see
 * {@code TransactionNormalizer}'s transactional amount hints). This is an ordinary movement of
 * money and belongs in the ledger.
 *
 * <p>{@link #BALANCE_MARKER}: no transactional column had a parseable value at all -- the only
 * reason this row normalized into anything is {@code TransactionNormalizer}'s last-resort
 * balance-column fallback (see its own doc comment on {@code AMOUNT_HINTS}: "so without this
 * fallback those two rows have a date but no recognizable amount and get silently dropped"). A
 * statement's {@code OPENING BALANCE}/{@code CLOSING BALANCE} label row is the motivating case,
 * but this says nothing about wording -- "Beginning Balance", "Balance Forward", "Balance b/f",
 * a blank description, or any other bank's phrasing all classify the same way, because the
 * classification never reads the label at all. It reads the row's own column structure: a real
 * transaction carries a value in a column that means "this transaction moved this much money";
 * a balance-marker row carries a value only in a column that means "the account stood at this
 * much afterward" (or before).
 *
 * <p>This is not a discard signal. A {@code BALANCE_MARKER} row still normalizes into a full
 * {@link com.finora.dto.ImportDto.StagedRow} with a real date/amount/description -- callers use
 * that to derive the statement's opening/closing balance (see {@code PdfPreviewGenerator}'s and
 * {@code StatementValidator}'s balance-point derivation, which reads every normalized row
 * regardless of kind). What {@link #BALANCE_MARKER} controls is narrower and more specific: such
 * a row must never be added to the list of rows offered to the user as an importable transaction
 * candidate, and must therefore never reach {@code ImportVerifier} as a row to verify, or the
 * client as a row it could echo back for persistence. See docs/architecture/system-design/
 * marker-row-pollution-scope-investigation.md for the full analysis this classification fixes.
 */
public enum RowKind {
    TRANSACTION,
    BALANCE_MARKER
}
