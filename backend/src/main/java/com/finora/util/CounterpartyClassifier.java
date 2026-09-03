package com.finora.util;

import java.util.regex.Pattern;

/**
 * Decides {@link CounterpartyType} from a narration alone -- no merchant lookup, no learned state,
 * no direction.
 *
 * <h2>Why this exists as its own layer</h2>
 *
 * <p>Measured on the real 29-statement corpus, of the 1,098 rows that still fell through to "Other"
 * after structural person detection shipped, <b>628 (57.2%) carried conclusive evidence of a
 * business counterparty</b> -- 543 a merchant-acquiring rail marker, a further 85 a corporate suffix
 * -- and every one of them was filed as "Other" anyway. The evidence was already being computed;
 * {@link PersonToPersonTransferDetector} used it only to VETO a person classification and then
 * discarded it. This class is that discarded signal, kept.
 *
 * <h2>Order is the design</h2>
 *
 * <p>The checks run most-conclusive first, and the order is not cosmetic:
 *
 * <ol>
 *   <li><b>Financial institution before business.</b> The business-token vocabulary deliberately
 *       contains BANK, FINANCE, INSURANCE, NBFC and AMC -- correct for vetoing a person, wrong as a
 *       final type. Checking FI first stops every bank charge and interest credit typing as a
 *       generic BUSINESS.</li>
 *   <li><b>Government before business</b>, for the same reason: a tax body is not a merchant, and
 *       several government narrations carry business-shaped tokens.</li>
 *   <li><b>Acquirer rail before corporate suffix.</b> A rail marker is structural evidence about how
 *       the money settled; a suffix is evidence about how a payee spells their name. When they
 *       disagree the rail is right, because a merchant QR cannot be collected on by an individual.</li>
 *   <li><b>Person last of the positive answers.</b> The detector's own known limitation is that a
 *       suffix-less 2-4 word brand is indistinguishable from a person's name, so anything with a
 *       business or institutional signal must be taken off the table before it is consulted.</li>
 * </ol>
 *
 * <p>Everything else is {@link CounterpartyType#UNKNOWN}. That is a real answer here, not a
 * failure: roughly 470 corpus rows carry no marker, no suffix and no name shape, and claiming a
 * type for them would be the confident-wrong-answer this codebase treats as worse than an honest
 * unknown.
 */
public final class CounterpartyClassifier {

    private CounterpartyClassifier() {}

    /**
     * Bank-generated activity, where the counterparty is the institution itself. These words are
     * about the MECHANISM (interest posting, a mandate debit, an ATM withdrawal, a charge), which is
     * why they outrank a payee-name signal: there is no payee.
     */
    private static final Pattern FINANCIAL_MECHANISM = Pattern.compile(
            // "int" as a bare token is included because that is how statements actually write
            // interest ("SB INT CREDIT", "INT CR"); the longer spellings alone matched none of it.
            "(?i)\\b(int|intcr|interest|intt|sbint|nach|ach[cd]r|ecs|mandate|atm|wdl|withdrawal"
            // Cashback and reward credits: the counterparty is the card issuer or the bank running
            // the programme, never a merchant and never a person. 18 of the 40 inbound rows in the
            // rail-less residue are these, so they were the largest single group there by count --
            // though near-zero by value, which is why they never surfaced in a value-weighted view.
            + "|cashback|rewards?|reward\\s*points?"
            + "|chrg|chrgs|charges|servicetax|folio|redemption|dividend)\\b");

    /** Institution-shaped payee names. Checked after the mechanism words above. */
    private static final Pattern FINANCIAL_ENTITY = Pattern.compile(
            "(?i)\\b(bank|nbfc|amc|broking|securities|insurance|assurance|mutualfunds?"
            + "|mutual\\s+fund|depository|cdsl|nsdl)\\b");

    /**
     * Government and tax bodies. Thinly evidenced (6 of 1,869 corpus rows), and kept narrow for that
     * reason -- every token here is unambiguous in Indian narrations, because a vaguer list would
     * type more rows wrongly than it typed rightly.
     */
    private static final Pattern GOVERNMENT = Pattern.compile(
            "(?i)\\b(gst|gstn|incometax|income\\s+tax|itd|tds|tcs\\s+challan|challan|epfo|epf"
            + "|uidai|cbdt|treasury|municipal|nagar\\s*nigam|panchayat|rto)\\b");

    /** Corporate suffixes proper -- narrower than the detector's full trade vocabulary. */
    private static final Pattern CORPORATE_SUFFIX = Pattern.compile(
            "(?i)\\b(pvt|private|ltd|limited|llp|inc|corp|corporation|enterprises?|ventures?"
            + "|technologies|solutions|industries|associates|holdings)\\b");

    /**
     * Types the counterparty behind a narration.
     *
     * @param description the raw narration; null or blank yields {@link CounterpartyType#UNKNOWN}
     */
    public static CounterpartyType classify(String description) {
        if (description == null || description.isBlank()) return CounterpartyType.UNKNOWN;

        if (FINANCIAL_MECHANISM.matcher(description).find()) return CounterpartyType.FINANCIAL_INSTITUTION;
        if (GOVERNMENT.matcher(description).find()) return CounterpartyType.GOVERNMENT;
        if (FINANCIAL_ENTITY.matcher(description).find()) return CounterpartyType.FINANCIAL_INSTITUTION;

        // Reuses the detector's own marker pattern rather than a second copy -- see
        // PersonToPersonTransferDetector.hasMerchantAcquirerMarker for why that matters.
        if (PersonToPersonTransferDetector.hasMerchantAcquirerMarker(description)) return CounterpartyType.BUSINESS;

        // A named merchant entity is business identity, full stop. Reached through
        // MerchantIdentityLookup rather than CategoryRules so this layer never depends on the
        // categorization engine: "Amazon is a known merchant" is the fact needed here, and "Amazon
        // means Shopping" is emphatically not. 130 corpus rows were recognised as a brand by the
        // category layer while this classifier still answered UNKNOWN -- an incoherent pair of
        // answers about the same row.
        if (MerchantIdentityLookup.namesKnownMerchant(description)) return CounterpartyType.BUSINESS;

        if (CORPORATE_SUFFIX.matcher(description).find()) return CounterpartyType.BUSINESS;

        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) return CounterpartyType.PERSON;

        // A trade word with no rail marker and no corporate suffix -- "MEDICAL", "SWEETS",
        // "TRAVELS". Weaker evidence than the two business checks above, so it sits BELOW the person
        // check rather than above it. That placement costs nothing: the detector already vetoes on
        // these same tokens, so any row reaching this line carrying one is a row the person check
        // has itself just declined to claim.
        if (PersonToPersonTransferDetector.hasBusinessToken(description)) return CounterpartyType.BUSINESS;

        return CounterpartyType.UNKNOWN;
    }
}
