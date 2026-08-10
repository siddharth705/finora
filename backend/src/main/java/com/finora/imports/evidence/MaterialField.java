package com.finora.imports.evidence;

/**
 * One field whose value materially affects an import -- ADR-006's generalization of
 * {@code com.finora.imports.product.ProductSignal} past product classification, to any extracted
 * value that needs its own evidence trail rather than being trusted as soon as a parser produces it.
 *
 * <p>Extend only as a real field needs it, the same discipline {@code ProductSignal}'s own growth
 * already follows -- this is not meant to enumerate every column a statement could ever have, only
 * the ones ADR-006's evidence model actually reasons about.
 */
public enum MaterialField {
    ACCOUNT_TYPE,
    ACCOUNT_NUMBER,
    ACCOUNT_HOLDER,
    BRANCH,
    IFSC,
    STATEMENT_PERIOD_START,
    STATEMENT_PERIOD_END,
    OPENING_BALANCE,
    CLOSING_BALANCE,
    CREDIT_LIMIT,
    PAYMENT_DUE_DATE,
    TRANSACTION_DATE,
    TRANSACTION_AMOUNT,
    TRANSACTION_DIRECTION,
    TRANSACTION_DESCRIPTION
}
