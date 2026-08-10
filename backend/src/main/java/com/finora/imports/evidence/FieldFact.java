package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * One observation of a {@link MaterialField}'s value, together with the provenance chain that
 * produced it -- ADR-006 §1's atomic unit of evidence. A {@link FieldCandidate} is built from one
 * or more of these; {@code EvidenceAssessor} decides whether they corroborate each other or share
 * a failure mode by comparing {@link #provenance}, never by comparing {@link #value} alone.
 *
 * @param provenance the chain of pipeline steps this observation is downstream of, outermost step
 *        last. Never empty -- a fact with no provenance cannot be assessed for independence.
 */
public record FieldFact<T>(MaterialField field, T value, List<ProvenanceNode> provenance) {

    public FieldFact {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("a FieldFact must carry at least one ProvenanceNode");
        }
        provenance = List.copyOf(provenance);
    }
}
