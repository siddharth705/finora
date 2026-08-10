package com.finora.imports.evidence;

/**
 * The outcome of asking whether two observations describe the same underlying fact -- ADR-006 §4a,
 * design §2.1. Deliberately three-valued, not boolean: same-fact correlation must be able to say
 * "cannot tell" without that silently collapsing into either "yes" or "no".
 */
public enum Correlation {
    /** The two observations describe the same fact -- eligible for {@link EvidenceAssessor} to
     *  treat as a corroborating (or contradicting, if their values differ) pair. */
    SAME_FACT,

    /** The two observations describe two different facts -- never compared against each other. */
    DIFFERENT_FACT,

    /** Not enough evidence to tell either way. Per design §2.4, an {@code UNCERTAIN} pair is
     *  excluded from evidence comparison entirely -- it is not treated as corroboration, and not
     *  treated as a contradiction. Equal values alone never upgrade this to {@code SAME_FACT}. */
    UNCERTAIN
}
