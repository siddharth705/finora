package com.finora.imports.evidence;

import com.finora.imports.product.EvidenceSource;
import java.util.Objects;

/**
 * A {@link FieldFact} paired with the structural {@link EvidenceSource} it was observed at --
 * design §3.1's input. Deliberately not a field added to {@link FieldFact} itself: {@code
 * EvidenceSource} (where in the document's structure a value was found -- document text, section
 * text, a row, a column header) is a third, independent axis from what {@link FieldFact} already
 * carries (the value, and {@link ProvenanceNode}'s "which pipeline step produced it"). Widening
 * {@link FieldFact} to also carry it would grow a Phase-A type for a Phase-C-only concern -- the
 * same discipline that kept {@link TransactionObservation}/{@link MetadataObservation} as their
 * own wrapper types in Phase B rather than widening {@code FieldFact} there either.
 */
public record SourcedFact<T>(FieldFact<T> fact, EvidenceSource source) {

    public SourcedFact {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(source, "source");
    }
}
