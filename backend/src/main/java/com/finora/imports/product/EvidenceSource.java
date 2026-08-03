package com.finora.imports.product;

/**
 * Where a fact was observed -- which is what decides how much it is worth.
 *
 * This distinction is the fix for a real misclassification. A combined statement opens with an
 * account-summary block enumerating every product the customer holds ("SAVINGS ACCOUNTS ...
 * DEPOSITS ... DEPOSITS"), and that block is physically adjacent to whichever table happens to come
 * first. Text near a section is not text ABOUT that section, and treating the two the same let a
 * document-level summary name a product for a section it had nothing to do with.
 *
 * Ordered weakest to strongest so {@link #isStrongerThan} is a plain ordinal comparison.
 */
public enum EvidenceSource {

    /**
     * Free text that describes the DOCUMENT rather than any one section -- a relationship summary,
     * a letterhead, a page banner. Worth very little on its own: it is exactly as close to the
     * section that follows it as to the one three pages later.
     */
    DOCUMENT_TEXT,

    /** Free text scoped to this section -- text between this section's own start and its table. */
    SECTION_TEXT,

    /** The section's own row data. */
    ROW_DATA,

    /**
     * The section's own column headers. The strongest source available: a column belongs to the
     * table that has it, with no ambiguity about scope at all.
     */
    COLUMN_HEADERS;

    public boolean isStrongerThan(EvidenceSource other) {
        return ordinal() > other.ordinal();
    }
}
