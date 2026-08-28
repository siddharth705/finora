package com.finora.service;

import com.finora.entity.Transaction;

/**
 * Static, per-source trust ranking. Phase 1 of the reconciliation roadmap
 * (docs/proposals/reconciliation-evolution-roadmap-proposal.md) -- how much a source is trusted
 * in general, independent of any specific match's own confidence. That pairing (source trust vs.
 * match confidence, kept as two separate numbers rather than blended into one) is Phase 2's
 * confidence engine; this is deliberately just three constants and a comparison, not a formula.
 *
 * <h2>Why these three values</h2>
 *
 * <p>{@code CSV_IMPORT} covers both CSV and PDF bank/credit-card statements (see {@link
 * Transaction.Source}'s own comment on the pre-existing PDF-tagged-as-CSV gap this doesn't fix) --
 * a statement is bank-verified, the highest-trust source that exists in this codebase today.
 * {@code GMAIL_IMPORT} is a parsed receipt email: real, but self-reported by the sender rather than
 * the bank. {@code MANUAL} is entirely self-reported, by the user.
 *
 * <p>An Account Aggregator source (proposed Phase 4, trust 100 -- higher than a statement, since
 * it's a live bank feed rather than a document someone chose to upload) does not exist in {@link
 * Transaction.Source} yet. There is deliberately no {@code default} branch below: adding a fourth
 * source without updating this switch is a compile error, not a silent trust-0 transaction.
 *
 * <h2>Where this is used</h2>
 *
 * <p>Today, only as {@link ReconciliationService}'s duplicate pass's canonical-selection tiebreak
 * -- when an exact-key duplicate group spans two sources (rare, but possible: a Gmail-parsed
 * receipt and the bank statement row it corresponds to can coincidentally share the same account/
 * date/amount/description), the higher-trust source's row becomes canonical, not whichever
 * happened to be created first. Ties (both rows from the same source) still fall back to creation
 * order, which is what made this deterministic before trust existed.
 */
final class SourceTrust {

    private SourceTrust() {}

    static int of(Transaction.Source source) {
        return switch (source) {
            case CSV_IMPORT -> 95;
            case GMAIL_IMPORT -> 60;
            case MANUAL -> 30;
        };
    }
}
