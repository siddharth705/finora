package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * Turns a field's raw {@link FieldFact}s into an {@link EvidenceStatus} -- design §1.1, ADR-006 §3's
 * central decision, kept as free functions rather than instance state so {@link FieldCandidate}'s
 * static factory can call it without any circularity (see {@link IndependenceRemediationPolicy}'s
 * doc for why the policy itself avoids taking a {@code FieldCandidate}).
 */
public final class EvidenceAssessor {

    private EvidenceAssessor() {
    }

    /**
     * Two provenance chains "share an upstream failure mode" iff they share any
     * {@link ProvenanceNode} at all -- same {@code Acquisition} run, same
     * {@code SectionAttribution} decision, or same {@code ColumnLayoutInterpretation}. This is
     * deliberately the simplest possible rule: a shared node means both facts passed through the
     * identical pipeline step, right or wrong, which is sufficient on its own to disqualify their
     * agreement as independent corroboration regardless of how many other nodes differ.
     */
    public static boolean shareAnUpstreamFailureMode(List<ProvenanceNode> a, List<ProvenanceNode> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.stream().anyMatch(b::contains);
    }

    /**
     * Derives the {@link EvidenceStatus} a field's facts jointly support, per design §1.1/§3.4/§3.5:
     * <ul>
     *   <li>Fewer than two facts: {@code INSUFFICIENT} -- a single observation, however strong its
     *       structural source, is never enough on its own (source strength alone is explicitly not
     *       sufficient for {@code SUPPORTED}, per round-1 review).</li>
     *   <li>Any two facts disagree on the value: {@code CONFLICTING} -- this holds regardless of
     *       whether the disagreeing pair is independent (a cross-source contradiction, design
     *       §4b {@code DISAGREE}) or shares an upstream node (the same-source-multiple-locations
     *       case design §3.4 also names as feeding {@code CONFLICTING}). A disagreement is a real
     *       contradiction either way; {@code policy} is never consulted about it, since remediation
     *       can only promote an agreeing pair, never explain away a conflict (design §3.5).</li>
     *   <li>Otherwise, at least one agreeing pair is independent, or shares an upstream node but
     *       {@code policy} clears it anyway: {@code SUPPORTED}.</li>
     *   <li>Otherwise (every agreeing pair shares an upstream node and none is cleared):
     *       {@code INSUFFICIENT} -- agreement alone, with no independent corroboration and no
     *       cleared remediation, is never enough (design §3.5's "no independent corroboration"
     *       case).</li>
     * </ul>
     */
    public static <T> EvidenceStatus deriveStatus(
            MaterialField field, List<FieldFact<T>> facts, IndependenceRemediationPolicy policy) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(policy, "policy");
        facts.forEach(f -> {
            if (f.field() != field) {
                throw new IllegalArgumentException(
                        "fact for " + f.field() + " passed to deriveStatus(" + field + ", ...)");
            }
        });

        if (facts.size() < 2) {
            return EvidenceStatus.INSUFFICIENT;
        }

        boolean anyDisagreement = false;
        boolean anySupportingPair = false;

        for (int i = 0; i < facts.size(); i++) {
            for (int j = i + 1; j < facts.size(); j++) {
                FieldFact<T> x = facts.get(i);
                FieldFact<T> y = facts.get(j);
                boolean independent = !shareAnUpstreamFailureMode(x.provenance(), y.provenance());
                boolean agree = Objects.equals(x.value(), y.value());

                if (!agree) {
                    anyDisagreement = true;
                } else if (independent || policy.remediate(field, x, y)) {
                    anySupportingPair = true;
                }
            }
        }

        if (anyDisagreement) {
            return EvidenceStatus.CONFLICTING;
        }
        if (anySupportingPair) {
            return EvidenceStatus.SUPPORTED;
        }
        return EvidenceStatus.INSUFFICIENT;
    }

    /** Convenience overload using {@link IndependenceRemediationPolicy#CONSERVATIVE_DEFAULT}. */
    public static <T> EvidenceStatus deriveStatus(MaterialField field, List<FieldFact<T>> facts) {
        return deriveStatus(field, facts, IndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }
}
