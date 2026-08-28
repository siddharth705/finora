package com.finora.service;

import java.math.BigDecimal;

/**
 * Every threshold the reconciliation passes decide by, in one place.
 *
 * <p><b>Why this exists.</b> These four numbers are business rules, not implementation detail —
 * "how long after a purchase can money come back and still be that purchase's refund" is a product
 * question with a product answer. Scattered through {@code ReconciliationService} as literals and
 * private constants, each was individually well-commented but there was nowhere to read them
 * together, and nowhere for the reasoning to live that was not also next to a loop.
 *
 * <p><b>This is a move, not a change.</b> Every value is exactly what it was. Changing any of them
 * is a product decision with real consequences for money already classified on real accounts —
 * widening {@link #REFUND_WINDOW_DAYS} reclassifies historical income the next time a user edits
 * anything, and narrowing it silently stops classifying refunds that used to be caught. Nothing
 * here should be edited to make a test pass.
 *
 * <p><b>Deliberately not configurable.</b> No {@code @ConfigurationProperties}, no database-backed
 * settings row. These are not knobs an operator should turn per environment: the same statement
 * must reconcile identically in dev, in a test, and in production, or a bug reproduced locally
 * means nothing. If a threshold ever genuinely needs to vary per user or per market, that is a
 * schema and product change, not a config file.
 */
public final class ReconciliationPolicy {

    private ReconciliationPolicy() {}

    /**
     * Plain amount+date transfer matches must fall within this many days of each other.
     *
     * <p>Transfers between a person's own accounts settle fast. Wider than this and ordinary
     * unrelated spending starts colliding: two accounts, opposite directions, similar amounts,
     * a fortnight apart describes a great deal of normal activity.
     */
    public static final long DEFAULT_TRANSFER_DAY_WINDOW = 4;

    /**
     * The widened window used when a known OWN_ACCOUNT relationship identifier appears on either
     * side of the pair.
     *
     * <p>Widened, not replaced — see docs/rule-engine-relationship-engine-eds.md §4, which asks
     * relationship evidence to "raise transfer-match confidence... not replace the heuristic".
     * A confirmed own-account identifier is strong enough evidence to tolerate a slower settling
     * period; it is not strong enough to match on its own with no date bound at all.
     */
    public static final long OWN_ACCOUNT_MATCH_DAY_WINDOW = 10;

    /**
     * How far apart a refund can land from the purchase it reverses.
     *
     * <p>Much wider than either transfer window, on purpose. Transfers between the user's own
     * accounts happen within days; merchant refunds — a return, a cancelled order, a billing
     * dispute — routinely take weeks, and a disputed card transaction can take months. This is the
     * one threshold where being too narrow fails silently: the refund simply stays classified as
     * ordinary income and nobody is told it was not matched.
     */
    public static final long REFUND_WINDOW_DAYS = 180;

    /**
     * How far apart two amounts can be and still count as "the same amount" for transfer matching.
     *
     * <p>Not zero, because a transfer between two of the user's own accounts does not always
     * arrive as the exact amount that left: a small fee is deducted in transit on some rails, and
     * rounding differs between institutions. Exclusive — a difference of exactly this much is NOT
     * the same amount — which is what keeps the rule from quietly widening by a rupee.
     *
     * <p>Refunds deliberately do not use this. A refund can be partial, so that pass compares with
     * "the refund cannot exceed the purchase" instead, and leans on merchant or keyword evidence
     * rather than amount closeness.
     */
    public static final BigDecimal TRANSFER_AMOUNT_TOLERANCE = BigDecimal.ONE;

    /**
     * How far a savings-side payment can land from a credit card statement's printed due date and
     * still be considered a candidate settlement of that statement -- roadmap Phase 3, "Credit card
     * settlement" (docs/proposals/reconciliation-evolution-roadmap-proposal.md Part 4).
     *
     * <p>Reuses {@link #OWN_ACCOUNT_MATCH_DAY_WINDOW}'s value rather than inventing a new number: a
     * card payment is routinely made several days early (auto-pay, a person clearing it as soon as
     * the statement arrives) or a few days late, and this is the same order of magnitude this file
     * already judged reasonable for "settles slower than an instant transfer, but is still clearly
     * the same event." Already covered by {@link #CANDIDATE_WINDOW_DAYS}'s existing max (dominated
     * by {@link #REFUND_WINDOW_DAYS}), so no change to that derivation was needed.
     */
    public static final long CC_PAYMENT_DUE_DATE_WINDOW_DAYS = OWN_ACCOUNT_MATCH_DAY_WINDOW;

    /**
     * How far either side of an import's own date range the candidate set has to reach for a
     * windowed reconciliation to find everything an unbounded one would.
     *
     * <p><b>Derived, never typed as a number.</b> It is the widest of the three matching windows
     * above, because a pass can only match a pair when BOTH rows are in the loaded set. Widen
     * {@link #REFUND_WINDOW_DAYS} and this follows automatically; hard-coding 180 here would let
     * the two drift and the failure would be silent — a refund that simply never matches.
     *
     * <p><b>Why symmetric.</b> The refund pass anchors on the INCOME row and looks backwards, so an
     * imported purchase can be the target of a refund that is already in the ledger up to this many
     * days <i>later</i> — normal whenever statements are imported out of order, which backfilling
     * during onboarding does routinely. An asymmetric window (−180/+10) reads as sufficient and
     * drops exactly those pairs.
     *
     * <p><b>What it guarantees.</b> Every pair involving at least one imported transaction. An
     * imported row sits at some date D within [min, max]; its partner is at most this many days
     * from D; so the partner is inside [min − W, max + W] and is loaded. What it deliberately does
     * NOT do is re-evaluate pairs of two pre-existing rows that both fall outside the window —
     * those were already evaluated when they were written.
     */
    public static final long CANDIDATE_WINDOW_DAYS =
            Math.max(REFUND_WINDOW_DAYS, Math.max(DEFAULT_TRANSFER_DAY_WINDOW, OWN_ACCOUNT_MATCH_DAY_WINDOW));
}
