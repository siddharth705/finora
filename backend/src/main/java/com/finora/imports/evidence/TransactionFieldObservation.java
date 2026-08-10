package com.finora.imports.evidence;

import com.finora.imports.product.EvidenceSource;
import java.util.Objects;

/**
 * One source's observation of one {@link MaterialField} of one transaction row -- the input the
 * end-to-end pipeline needs, bundling what {@link TransactionFactCorrelator} needs (the row's
 * {@link #position}, to decide whether two observations describe the same row) with what
 * {@link DimensionAssessor} needs (the field's {@link #fact}, and its {@link #evidenceSource}).
 *
 * <p>Deliberately not folded into {@link TransactionObservation} itself: that type models a whole
 * row's correlation-relevant signals (date/amount/direction/description/geometry/ordinal) as a
 * single physical thing, which is correct for {@code TransactionFactCorrelator} -- it does not
 * know or care which single field is currently being assessed. This type is one layer up: "this
 * particular field's value, observed as part of that row."
 */
public record TransactionFieldObservation<T>(TransactionObservation position, FieldFact<T> fact,
        EvidenceSource evidenceSource) {

    public TransactionFieldObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
    }
}
