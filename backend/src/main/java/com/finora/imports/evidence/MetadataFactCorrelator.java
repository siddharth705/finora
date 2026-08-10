package com.finora.imports.evidence;

import java.util.Objects;

/**
 * Decides whether two {@link MetadataObservation}s describe the same metadata value -- design §2.3.
 * Simpler than {@link TransactionFactCorrelator}: a metadata field has no "row" to disambiguate
 * against, only field identity, section identity, and where/how the value was labeled.
 */
public final class MetadataFactCorrelator {

    // float, deliberately matching BoundingBox#overlapRatio's return type -- see
    // TransactionFactCorrelator#GEOMETRY_OVERLAP_THRESHOLD's comment for why comparing a float
    // overlap ratio against a double literal is a real, verified boundary bug, not a style nit.
    private static final float REGION_PROXIMITY_THRESHOLD = 0.3f;
    private static final double SEMANTIC_CONTEXT_THRESHOLD = 0.6;

    private MetadataFactCorrelator() {
    }

    public static <T> Correlation correlate(MetadataObservation<T> a, MetadataObservation<T> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        // Field and section identity are hard gates, for the same reason page is a hard gate for
        // transactions: the ICICI case this whole ADR chain traces back to was exactly a
        // section-identity failure, and no proximity/semantic signal below may override this.
        if (a.fact().field() != b.fact().field()) {
            return Correlation.DIFFERENT_FACT;
        }
        if (a.sectionIndex() != b.sectionIndex()) {
            return Correlation.DIFFERENT_FACT;
        }

        boolean regionComparable = a.region() != null && b.region() != null;
        boolean regionClose = regionComparable && a.region().overlapRatio(b.region()) > REGION_PROXIMITY_THRESHOLD;

        double semanticSimilarity = TextSimilarity.tokenOverlapRatio(a.semanticContext(), b.semanticContext());
        boolean semanticMatches = semanticSimilarity > SEMANTIC_CONTEXT_THRESHOLD;

        if (regionClose || semanticMatches) {
            return Correlation.SAME_FACT;
        }
        // Covers both "neither region proximity nor semantic context could be computed
        // confidently" and "they were computed and actively disagree" -- per design §2.3, both
        // land on UNCERTAIN, not DIFFERENT_FACT, since the hard gates already confirmed field and
        // section identity; only the fact instance itself is ambiguous.
        return Correlation.UNCERTAIN;
    }
}
