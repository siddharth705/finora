package com.finora.imports.trust;

import java.util.List;

/**
 * Whether an import is quarantined, and every reason that fired.
 *
 * <p>Carries all of them rather than the first. An operator opening a hold is deciding whether the
 * extraction is wrong, and "the counts disagree AND the period is impossible" is a different
 * situation from either alone -- it usually means the document was misread structurally rather
 * than in one place.
 *
 * @param hold    whether the import must be withheld from the user's confirm step
 * @param reasons one human-readable sentence per condition that fired, in evaluation order and
 *                de-duplicated; empty exactly when {@code hold} is false
 */
public record HoldDecision(boolean hold, List<String> reasons) {

    /** The overwhelmingly common answer: nothing fired, the import proceeds untouched. */
    public static final HoldDecision RELEASE = new HoldDecision(false, List.of());

    public HoldDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /**
     * The one line stored on {@code held_statements.trigger_summary} and shown to an operator.
     *
     * <p>Written for someone who was not here when it fired and cannot re-run the import to find
     * out: it names what was observed, not what to do about it.
     */
    public String summary() {
        return String.join("; ", reasons);
    }
}
