package com.finora.imports.product;

/**
 * One structural fact a financial document can exhibit.
 *
 * These are deliberately OBSERVATIONS, not conclusions. {@code MATURITY_FIELD} means "a maturity
 * field is present here", never "this is a deposit" -- what a maturity field implies is the
 * classifier's business (Stage 2), and it depends on what else is present alongside it. Both fixed
 * and recurring deposits have maturity dates; only one of them has installments.
 *
 * Keeping the vocabulary at this level is what makes the engine multi-signal rather than
 * keyword-driven. A signal is evidence; no single one is an answer.
 */
public enum ProductSignal {

    // --- Ledger structure ----------------------------------------------------------------------
    /** A column holding one transaction date per row. */
    DATE_COLUMN,
    /** A free-text narration/particulars/description column -- what makes a table a ledger of
     *  events rather than a schedule of figures. */
    DESCRIPTION_COLUMN,
    /** Separate money-out and money-in columns (withdrawal/deposit, debit/credit). */
    DEBIT_CREDIT_COLUMNS,
    /** One signed amount column instead of a debit/credit pair. */
    SINGLE_AMOUNT_COLUMN,
    /** A balance-after-each-transaction column. */
    RUNNING_BALANCE_COLUMN,
    /** The section produced parseable rows. */
    TRANSACTION_ROWS,

    // --- Period balances ------------------------------------------------------------------------
    OPENING_BALANCE_FIELD,
    CLOSING_BALANCE_FIELD,

    // --- Deposit fields ---------------------------------------------------------------------------
    MATURITY_FIELD,
    INTEREST_RATE_FIELD,
    PRINCIPAL_FIELD,
    /** A per-period contribution -- the field that separates a recurring deposit from a fixed one. */
    INSTALLMENT_FIELD,

    // --- Credit fields ----------------------------------------------------------------------------
    MINIMUM_DUE_FIELD,
    TOTAL_DUE_FIELD,
    CREDIT_LIMIT_FIELD,
    CARD_NUMBER_FIELD,

    // --- Borrowing fields --------------------------------------------------------------------------
    EMI_FIELD,
    OUTSTANDING_FIELD,
    TENURE_FIELD,

    /**
     * The document names a product in words ("Recurring Deposit", "Credit Card").
     *
     * Carried as ONE signal for every product rather than one per product, because where the name
     * was found matters more than which name it was -- see {@link EvidenceSource}. A name in this
     * section's own column headers is close to decisive; the same name in a document-level summary
     * that enumerates everything the customer holds is nearly worthless, and the two used to be
     * indistinguishable.
     */
    PRODUCT_NAME
}
