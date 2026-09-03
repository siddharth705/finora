package com.finora.util;

/**
 * What kind of entity was on the other side of a transaction.
 *
 * <p>This is deliberately NOT a category. A category answers "what was this money for", which for a
 * large share of real narrations is genuinely unknowable; this answers "who was this with", which is
 * very often knowable from the same text. The two were conflated while categorization was the only
 * output, and the cost of conflating them is visible in the corpus: the merchant-acquiring rail
 * markers were already being detected and then used only to VETO a person classification, so a
 * measured 543 rows carried conclusive evidence of a business counterparty that was thrown away and
 * filed as "Other".
 *
 * <p><b>Direction is not encoded here, on purpose.</b> It is already {@code txn_type} on the
 * transaction. Encoding it a second time is exactly the mistake V123 made by naming a category
 * "Paid a Person" -- the detector has never inspected direction, so 22.8% of that category was money
 * received being described as money paid. A caller that wants "Sent to a person" / "Received from a
 * person" composes this type with the amount's sign rather than storing a third fact that can
 * disagree with the first two.
 */
public enum CounterpartyType {

    /** A named individual. Not "a friend" and not "a payee" -- purpose is a separate question. */
    PERSON,

    /**
     * A business. Includes the small merchant who collects on a personal-looking handle but settles
     * over a merchant-acquiring rail, which is the single largest identifiable group in the corpus.
     */
    BUSINESS,

    /**
     * A bank, broker, AMC, NBFC or insurer -- including the statement issuer itself, which is the
     * counterparty for interest credits, charges and ATM activity.
     */
    FINANCIAL_INSTITUTION,

    /**
     * A tax authority or government body. Thinly evidenced on the current corpus (6 of 1,869 rows),
     * so it is a real but rare type rather than a major bucket -- kept separate from BUSINESS
     * because the two behave differently for anything a user would later ask about deductions.
     */
    GOVERNMENT,

    /**
     * Nothing in the narration establishes who was on the other side. An honest answer, and the one
     * this codebase prefers to a confident wrong one.
     */
    UNKNOWN
}
