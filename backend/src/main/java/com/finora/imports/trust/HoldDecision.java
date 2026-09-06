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
 * @param hold       whether the import must be withheld from the user's confirm step
 * @param reasons    one human-readable sentence per condition that fired, in evaluation order and
 *                   de-duplicated; empty exactly when {@code hold} is false
 * @param categories the machine-readable tag behind each reason, de-duplicated; empty exactly when
 *                   {@code hold} is false. See {@link TrustPredicate.Category}.
 */
public record HoldDecision(boolean hold, List<String> reasons, List<TrustPredicate.Category> categories) {

    /** The overwhelmingly common answer: nothing fired, the import proceeds untouched. */
    public static final HoldDecision RELEASE = new HoldDecision(false, List.of(), List.of());

    public HoldDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** Convenience constructor for callers that have no category to report -- {@code
     *  HeldStatementService.rerunParser}'s extraction-failure case is the one real example: it is
     *  not one of {@link TrustPredicate}'s three conditions, so it legitimately has none. Kept so
     *  every call site written before this plan keeps compiling unchanged. */
    public HoldDecision(boolean hold, List<String> reasons) {
        this(hold, reasons, List.of());
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
