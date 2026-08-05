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
}
