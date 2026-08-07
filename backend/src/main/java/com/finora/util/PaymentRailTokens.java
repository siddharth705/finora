package com.finora.util;

import java.util.Set;

/**
 * The words in a bank narration that name a payment RAIL or INSTRUMENT rather than a counterparty.
 *
 * <p><b>Reusable vocabulary, not a point patch.</b> Indian bank narrations are overwhelmingly
 * shaped {@code <rail>/<reference>/<counterparty>} — "UPI/9182736/SWIGGY",
 * "NEFT-HDFC0XXXXXX-ACME LTD", "POS 1234 BIGBASKET". Any code that tries to identify WHO a
 * transaction was with has to skip the rail first, and before this class existed the one place
 * that did so had no way to say what a rail was, so it did not skip anything.
 *
 * <h2>The bug this exists to prevent</h2>
 * {@code MerchantNormalizationEngine} groups a never-before-seen description onto an existing
 * merchant by the first "significant" token of the normalised text, where significant meant only
 * "longer than two characters". Every token in this set is three or more characters, so on a real
 * Indian statement the grouping key for essentially every row was the rail — {@code upi} — and the
 * merchant it matched was whichever UPI payee happened to be seen first. Every subsequent UPI
 * transaction, to any payee, aliased onto that one merchant.
 *
 * <p>That is not a cosmetic grouping error. A merchant's confirmation counts are what
 * {@code ConfidenceEngine.topCategory} reads to decide which category is auto-applied, so a
 * merchant that has absorbed hundreds of unrelated payees teaches the learning engine a category
 * drawn from all of them at once.
 *
 * <h2>Why a deny-list rather than something cleverer</h2>
 * The alternative — inferring which token is the counterparty from position, casing or a merchant
 * dictionary — is exactly the fuzzy matching {@code MerchantNormalizationEngine}'s own class doc
 * rules out ("a deliberately simple heuristic, not fuzzy matching or NLP"). This keeps that
 * property: it is a fixed, readable list of words that are never a counterparty name, and
 * everything else is left alone.
 *
 * <p><b>Failing this check is the safe direction.</b> A rail token wrongly left in the set costs a
 * merchant that does not group with its own variants — a duplicate the user can see and merge by
 * hand, which is what the merge feature exists for. A rail token wrongly left OUT costs silent
 * over-grouping, which is invisible and corrupts the learning distribution. The two are not
 * symmetric, so this list errs toward including a token when in doubt.
 */
public final class PaymentRailTokens {

    private PaymentRailTokens() {}

    /**
     * Matched against a single already-normalised token (lowercase, alphanumeric) — never against
     * a whole description, so a merchant genuinely named "Ach Foods" is unaffected unless "ach" is
     * its FIRST token, and even then the cost is a merchant that does not auto-group.
     */
    private static final Set<String> RAIL_TOKENS = Set.of(
            // Interbank and clearing rails.
            "upi", "neft", "imps", "rtgs", "ach", "nach", "ecs", "eft", "clg", "byclg",

            // Instruments and channels the narration names before it names the payee.
            "atm", "pos", "chq", "cheque", "inb", "tpt", "ift", "mmt", "vps", "wdl",

            // Generic transaction verbs that lead a narration for the same structural reason a
            // rail does: "PAYMENT TO SWIGGY" and "PAYMENT TO ZOMATO" collapse onto "payment"
            // exactly as "UPI/../SWIGGY" and "UPI/../ZOMATO" collapse onto "upi".
            "payment", "transfer", "withdrawal", "purchase", "txn", "trf", "ref"
    );

    /**
     * True when {@code token} names how money moved rather than who it moved to.
     *
     * @param token a single normalised token; null and blank are not rail tokens, so a caller that
     *              passes one gets {@code false} and decides for itself
     */
    public static boolean isRailToken(String token) {
        if (token == null || token.isBlank()) return false;
        return RAIL_TOKENS.contains(token.toLowerCase());
    }
}
