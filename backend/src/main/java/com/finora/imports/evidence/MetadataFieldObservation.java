package com.finora.imports.evidence;

import com.finora.imports.product.EvidenceSource;
import java.util.Objects;

/**
 * A {@link MetadataObservation} paired with the {@link EvidenceSource} it needs for
 * {@link DimensionAssessor#assessStructural} -- the metadata-field analogue of
 * {@link TransactionFieldObservation}. Unlike the transaction case, {@link MetadataObservation}
 * already wraps the {@link FieldFact}, so nothing else needs bundling here.
 */
public record MetadataFieldObservation<T>(MetadataObservation<T> position, EvidenceSource evidenceSource) {

    public MetadataFieldObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
    }
}
