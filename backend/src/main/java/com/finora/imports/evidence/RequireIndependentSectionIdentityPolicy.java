package com.finora.imports.evidence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Design §3.5's named default remediation policy: two things sharing a {@code SectionAttribution}
 * node may still count as independent if that section's own identity was independently confirmed
 * by a check that does not itself descend from the same {@code SectionAttribution} node --
 * concretely, a product/section-identity validator's confirmation for that section (e.g.
 * {@code ProductValidator}'s {@code VALIDATED} outcome).
 *
 * <p>Provides both grains from one shared input: {@link #of} for two {@link DimensionResult}s
 * (design §3.5's own stated grain) and {@link #ofFacts} for two {@link FieldFact}s
 * ({@link EvidenceAssessor}/{@link DimensionAssessor#assessCorroboration}'s internal grain). A
 * section's confirmation must mean the same thing at both grains -- two separate policy objects
 * built from the same confirmed-section set, not two independent judgments about the same section,
 * found necessary during the Phase-C end-to-end review when a shared {@code SectionAttribution}
 * inside a same-fact group needed clearing consistently with the outer dimension-level check.
 *
 * <p>Deliberately takes the confirmed-section set as a plain caller-supplied input rather than
 * depending on any specific validator type directly: this mechanism's job is only "was this
 * section's identity independently confirmed, yes or no" -- which validator makes that
 * determination, and how, is a decision for whoever wires this policy per import, not something
 * the independence mechanism itself should hard-code a dependency on.
 *
 * <p><b>Conservative by construction, not merely by default value:</b> this policy clears a pair
 * only when EVERY node the two share is a {@code SectionAttribution} node whose section is
 * confirmed -- if they share any other kind of node (an {@code Acquisition} or a
 * {@code ColumnLayoutInterpretation}), or a {@code SectionAttribution} for an unconfirmed section,
 * remediation refuses. A policy addressing one specific failure mode must not silently vouch for a
 * different one it was never shown evidence about.
 */
public final class RequireIndependentSectionIdentityPolicy {

    private RequireIndependentSectionIdentityPolicy() {
    }

    public static DimensionIndependenceRemediationPolicy of(Set<Integer> independentlyConfirmedSectionIndexes) {
        Set<Integer> confirmed = confirmedCopy(independentlyConfirmedSectionIndexes);
        return (a, b) -> clears(a.provenance(), b.provenance(), confirmed);
    }

    public static IndependenceRemediationPolicy ofFacts(Set<Integer> independentlyConfirmedSectionIndexes) {
        Set<Integer> confirmed = confirmedCopy(independentlyConfirmedSectionIndexes);
        return (field, a, b) -> clears(a.provenance(), b.provenance(), confirmed);
    }

    private static Set<Integer> confirmedCopy(Set<Integer> independentlyConfirmedSectionIndexes) {
        Objects.requireNonNull(independentlyConfirmedSectionIndexes, "independentlyConfirmedSectionIndexes");
        return Set.copyOf(independentlyConfirmedSectionIndexes);
    }

    private static boolean clears(List<ProvenanceNode> a, List<ProvenanceNode> b, Set<Integer> confirmed) {
        Set<ProvenanceNode> shared = sharedNodes(a, b);
        if (shared.isEmpty()) {
            return false;
        }
        for (ProvenanceNode node : shared) {
            if (!(node instanceof ProvenanceNode.SectionAttribution sectionNode)) {
                return false;
            }
            if (!confirmed.contains(sectionNode.sectionIndex())) {
                return false;
            }
        }
        return true;
    }

    private static Set<ProvenanceNode> sharedNodes(List<ProvenanceNode> a, List<ProvenanceNode> b) {
        Set<ProvenanceNode> shared = new HashSet<>(a);
        shared.retainAll(new HashSet<>(b));
        return shared;
    }
}
