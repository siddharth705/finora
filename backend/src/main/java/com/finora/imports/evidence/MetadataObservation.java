package com.finora.imports.evidence;

import java.util.Objects;

/**
 * One acquisition's reading of one metadata {@link FieldFact}, plus the correlation-only context
 * (§2.3) that {@code FieldFact} itself doesn't carry -- which section it was read from, and where
 * on the page / in what surrounding label text. Kept separate from {@link FieldFact} for the same
 * reason {@link TransactionObservation} is kept separate from it: correlation needs signals Phase
 * A's evidence-status derivation never needed.
 *
 * @param sectionIndex which {@code LocatedSection} (by list position) this observation was read
 *                      from -- a hard gate in §2.3, same discipline as {@link TransactionObservation}'s
 *                      page hard gate, chosen for the identical reason: the ICICI bug this ADR chain
 *                      traces back to was a section-identity failure, so nothing here may paper over
 *                      a section mismatch.
 * @param region nullable -- the source text's bounding box, when the acquisition preserved geometry
 * @param semanticContext nullable -- the label text observed near the value (e.g. "Credit Limit:")
 */
public record MetadataObservation<T>(FieldFact<T> fact, int sectionIndex, BoundingBox region, String semanticContext) {

    public MetadataObservation {
        Objects.requireNonNull(fact, "fact");
        if (sectionIndex < 0) {
            throw new IllegalArgumentException("sectionIndex must be non-negative");
        }
    }
}
