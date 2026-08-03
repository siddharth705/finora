package com.finora.imports.product;

/**
 * One thing the document was observed to contain, and where.
 *
 * A fact is NEUTRAL. It carries no opinion about what the section is -- it becomes positive,
 * negative or contradictory evidence only when a hypothesis is tested against it (see
 * {@link ProductHypothesis}). The same {@code MATURITY_FIELD} fact corroborates a fixed deposit and
 * contradicts a savings account, and neither meaning belongs in the fact itself.
 *
 * {@code observed} keeps the literal text that produced the fact, so a misclassification can be
 * argued with rather than re-derived by re-reading the PDF.
 *
 * @param signal   what was observed
 * @param source   where it was observed, which decides its weight
 * @param observed the literal text behind it, for explainability
 * @param named    for {@link ProductSignal#PRODUCT_NAME} only: the product the words point at.
 *                 Null for every structural signal. Whether a naming is BELIEVED is the
 *                 classifier's decision -- recording it here is not endorsing it.
 */
public record ObservedFact(ProductSignal signal, EvidenceSource source, String observed,
                           FinancialProductType named) {

    public static ObservedFact of(ProductSignal signal, EvidenceSource source, String observed) {
        return new ObservedFact(signal, source, observed, null);
    }

    public static ObservedFact productName(FinancialProductType named, EvidenceSource source,
                                           String observed) {
        return new ObservedFact(ProductSignal.PRODUCT_NAME, source, observed, named);
    }

    /** Reads as one line of an explanation, e.g. {@code MATURITY_FIELD in column headers
     *  ("Maturity Date")}. */
    public String describe() {
        return signal + " in " + source.name().toLowerCase().replace('_', ' ')
                + " (\"" + observed + "\")";
    }
}
