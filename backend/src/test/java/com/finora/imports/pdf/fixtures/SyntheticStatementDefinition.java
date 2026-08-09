package com.finora.imports.pdf.fixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a synthetic statement is INTENDED to contain, stated once, upstream of everything.
 *
 * <h2>Why this exists rather than asking the builder what it drew</h2>
 *
 * A ground truth derived from the fixture generator is independent of the parser and NOT independent
 * of the generator. A defect in the drawing code would make the expected values wrong in exactly the
 * same way as the rendered document, and the test would pass while proving nothing. So the intent is
 * declared first, and both the PDF and the ground-truth document are produced FROM it:
 *
 * <pre>
 *   SyntheticStatementDefinition
 *        |                    |
 *        v                    v
 *   ground-truth JSON    rendered PDF
 * </pre>
 *
 * Neither branch reads the other. That independence is itself asserted -- see
 * {@code SyntheticGroundTruthTest.theGroundTruthDocumentIsProducedWithoutRenderingAnything}.
 *
 * <h2>Financial truth and layout truth are separate</h2>
 *
 * {@code entities} answers "what should exist" and is the only thing the ground-truth matcher reads.
 * {@link LayoutGroundTruth} answers "where should it have been printed", which is what character
 * recognition will eventually be judged against. Folding coordinates into the financial model would
 * couple the two: native extraction and OCR produce different coordinate systems for the same
 * correct answer, and a financial ground truth that disagreed with one of them would be wrong for
 * reasons that have nothing to do with money.
 */
public record SyntheticStatementDefinition(String documentId, List<Entity> entities,
                                            List<LayoutGroundTruth> layout) {

    /** What the matcher's own vocabulary calls a thing the document should contain. */
    public record Entity(String id, String product, Presence presence, String accountNumberMasked,
                          ZeroTransactions zeroTransactionsLegitimate, List<Row> rows) {

        /** The count is DERIVED from the rows this entity declares, never counted from output. */
        public int expectedTransactions() {
            return rows.size();
        }
    }

    public enum Presence { DETECTED, ABSENT }

    /**
     * Never a bare boolean, matching the ground-truth model's own rule: "rows == 0 therefore zero is
     * legitimate" is the original defect with a field name attached. UNKNOWN is first-class and never
     * defaults to TRUE.
     */
    public enum ZeroTransactions { TRUE, FALSE, UNKNOWN }

    /** One line of a ledger, as the definition intends it -- not as anything read it back. */
    public record Row(LocalDate date, String description, BigDecimal amount, boolean credit) {}

    /**
     * Where the definition intends a piece of text to be printed. Deliberately a REGION rather than
     * a glyph box: recognition will not reproduce PDF glyph geometry, and demanding that it does
     * would fail a correct reading for being differently measured.
     */
    public record LayoutGroundTruth(int page, String expectedText, float xFrom, float xTo,
                                     float yFrom, float yTo) {

        public boolean contains(float x, float y) {
            return x >= xFrom && x <= xTo && y >= yFrom && y <= yTo;
        }
    }
}
