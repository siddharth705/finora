package com.finora.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects a person-to-person transfer narration structurally -- no merchant lookup, no keyword
 * table, just the shape of the text -- for the single largest bucket found in a real-corpus
 * measurement of Finora's "Other" categorization outcomes (see
 * docs/superpowers/specs/2026-09-01-transaction-categorization-design.md §1): 42.2% of every
 * "Other" transaction is a UPI/NEFT/IMPS/RTGS transfer naming an individual, not a business. No
 * keyword list, no merchant corpus, no LLM can ever categorize these correctly by merchant lookup,
 * because there is no merchant -- this is a taxonomy answer ("Transfer"), not a smarter match.
 *
 * <p>The detection logic below (business-signal gate, name-token shape, transfer-marker gate) is
 * a direct port of a heuristic validated against the full 1,400-transaction real "Other"
 * population from Finora's bank-statement corpus, spot-checked by hand against 110 items (~8% of
 * the population) for an honest error bound of roughly 8-12%, split across two disclosed,
 * partially-offsetting biases: a lone first name is conservatively NOT counted as a person
 * (undercounts P2P) and a multi-word brand name with no recognizable business-descriptor word can
 * occasionally look like a person (overcounts P2P). See the spec's §1 "Validated at scale"
 * section for the full methodology.
 *
 * <p>Deliberately conservative: only fires when an explicit transfer-protocol marker (UPI/NEFT/
 * IMPS/RTGS) is present. A bare person-shaped word with no transfer context is too weak a signal
 * -- it could just as easily be part of a merchant's trade name -- so this never touches a
 * narration without one of those markers.
 */
public final class PersonToPersonTransferDetector {

    private PersonToPersonTransferDetector() {}

    // Interbank/UPI transfer-protocol markers -- the ONLY context this detector will ever fire in.
    // Deliberately narrower than PaymentRailTokens.RAIL_TOKENS (which also includes ATM/POS/CHQ,
    // none of which imply a person-to-person transfer -- an ATM withdrawal is never a P2P
    // transfer, so reusing that broader set here would be wrong, not just imprecise).
    private static final Pattern TRANSFER_MARKER = Pattern.compile("\\b(UPI|NEFT|IMPS|RTGS)\\b");

    // A statement's own bank sometimes prefixes its own institution name onto the line (e.g.
    // "HDFC BANK LIMITED UPI-<person name>-..."). Left in, "BANK"/"LIMITED" spuriously read as a
    // business signal for a line whose actual counterparty is a person. Stripped before any other
    // check runs.
    private static final Pattern OWN_BANK_PREFIX = Pattern.compile(
            "^[A-Za-z][A-Za-z]*\\s+BANK\\s+(LIMITED|LTD)\\.?\\s*", Pattern.CASE_INSENSITIVE);

    // Business-entity suffix/trade-name words -- checked GLOBALLY across the whole narration (not
    // per-segment), because a genuine business name can span multiple delimiter-separated segments
    // ("SHARMA TRADERS-PVT LTD") while still being one entity, not a person plus an unrelated
    // coincidence sitting in a neighbouring segment.
    private static final Set<String> BUSINESS_SUFFIX_TOKENS = Set.of(
            "PVT", "LTD", "LLP", "LIMITED", "PRIVATE", "ENTERPRISES", "STORES", "STORE",
            "SERVICES", "SERVICE", "SOLUTIONS", "TRADERS", "ASSOCIATES", "AGENCIES",
            "INDUSTRIES", "EXPORTS", "IMPORTS", "GROUP", "FOUNDATION", "TRUST", "SOCIETY",
            "HOSPITAL", "CLINIC", "PHARMACY", "SCHOOL", "COLLEGE", "UNIVERSITY", "FINANCE",
            "INSURANCE", "FUND", "COMPANY", "CORP", "CORPORATION", "MART", "SHOP",
            "MANAGEMENT", "ASSET", "NSE", "BSE", "SCHEME", "NGO", "PROJECTS", "SYSTEMS",
            "TECHNOLOGIES", "TECH", "LABS", "CONSULTANCY", "CONSULTING", "VENTURES",
            "CAPITAL", "HOLDINGS", "AND", "CO", "HOUSE", "FOODS", "FOOD", "KITCHEN", "CAFE",
            "HOTEL", "RESTAURANT", "SWEETS", "BAKERY", "DAIRY", "GENERAL", "MEDICAL",
            "WORLD", "ZONE", "CENTRE", "CENTER", "POINT", "CORNER", "PLAZA", "MALL",
            "BAZAAR", "BAZAR", "COLLECTIONS", "BOUTIQUE", "JEWELLERS", "ELECTRONICS",
            "MOBILES", "MOTORS", "AUTOMOBILES", "CONSTRUCTION", "BUILDERS", "PROPERTIES",
            "REALTY", "INFRA", "INFRASTRUCTURE", "ACADEMY", "INSTITUTE", "COACHING",
            "LAUNDRY", "SALON", "SPA", "GYM", "FITNESS", "STUDIO", "PRINTERS", "PRINTING",
            "STATIONERY", "HARDWARE", "ELECTRICALS", "TRAVELS", "TOURS", "CARGO",
            "LOGISTICS", "TRANSPORT", "COURIER", "BANK", "NBFC", "AMC", "MUTUAL"
    );

    // Banking/protocol/channel abbreviations and routine transaction-flow boilerplate -- never
    // part of a person's name, but expected in EVERY narration (every UPI transfer literally
    // contains "UPI"), so these are excluded only PER-WORD inside looksLikePersonName, never as a
    // global gate the way BUSINESS_SUFFIX_TOKENS is.
    private static final Set<String> PROTOCOL_AND_BOILERPLATE_TOKENS = Set.of(
            "UPI", "NEFT", "IMPS", "RTGS", "ATM", "POS", "ACH", "NACH", "ECS", "MOB", "INR",
            "INDIA", "PAYMENT", "PAYMENTS", "PYMT", "PYMNT", "TRANSFER", "TRF", "TXN",
            "REF", "RRN", "CHG", "CHGS", "BANKING", "BR", "BRANCH", "DEBIT", "CREDIT",
            "CR", "DR", "NO", "ACC", "ACCT", "THE", "FOR", "TO", "FROM", "WITH", "VIA",
            "PAID", "VALUE", "DT", "DATE", "CHQ", "IB", "INTENT", "ID", "PHONE"
    );

    // PSP/wallet/fintech brand tokens -- channels, not people, but likewise expected boilerplate
    // rather than a global business signal (a PERSON'S transfer routed via PhonePe still names
    // "PHONEPE" as a segment of its own).
    private static final Set<String> PSP_BRAND_TOKENS = Set.of(
            "PHONEPE", "RAZORPAY", "BHARATPE", "PAYTM", "PAYTMQR", "GPAY", "GOOGLEPAY",
            "CRED", "MOBIKWIK", "FREECHARGE", "WHATSAPP", "AMAZONPAY"
    );

    // The union of every token that can never be part of a person's given/family name -- used to
    // disqualify individual words when testing whether ONE segment looks like a name.
    private static final Set<String> NON_NAME_TOKENS = new HashSet<>();
    static {
        NON_NAME_TOKENS.addAll(BUSINESS_SUFFIX_TOKENS);
        NON_NAME_TOKENS.addAll(PROTOCOL_AND_BOILERPLATE_TOKENS);
        NON_NAME_TOKENS.addAll(PSP_BRAND_TOKENS);
    }

    // A VPA-shaped handle whose local part contains "qr" -- a merchant-QR handle (e.g.
    // "paytmqr6nu5ur@ptys"), never a person's own UPI handle. A structural business signal
    // independent of the word-token checks above.
    private static final Pattern VPA_BUSINESS_QR = Pattern.compile(
            "(?i)[a-z0-9._-]*qr[a-z0-9._-]*@[a-z0-9]+");

    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z]{2,15}");

    /**
     * True when {@code description} structurally looks like a UPI/NEFT/IMPS/RTGS transfer to a
     * named individual rather than a business -- see this class's own doc comment for the
     * evidence and the conservative gate this stays behind.
     */
    public static boolean isNamedIndividualTransfer(String description) {
        if (description == null || description.isBlank()) return false;
        if (!TRANSFER_MARKER.matcher(description.toUpperCase()).find()) return false;
        if (VPA_BUSINESS_QR.matcher(description).find()) return false;

        String body = OWN_BANK_PREFIX.matcher(description).replaceFirst("");
        if (containsBusinessSignal(body.toUpperCase())) return false;

        for (String segment : body.split("[\\-/_]+")) {
            if (looksLikePersonName(segment.trim())) return true;
        }
        return false;
    }

    /** True when a {@link #BUSINESS_SUFFIX_TOKENS} word appears anywhere in the text, as a whole
     *  word -- checked across the FULL narration, not per-segment (see that set's own comment). */
    private static boolean containsBusinessSignal(String textUpper) {
        for (String token : BUSINESS_SUFFIX_TOKENS) {
            if (Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(textUpper).find()) {
                return true;
            }
        }
        return false;
    }

    /** 2-4 real words (2-15 letters each), each not a business/protocol/brand token, with any
     *  additional single-letter tokens treated as name initials (a real, common Indian-naming
     *  convention, e.g. "R BAGAVATHI") rather than disqualifying the segment. */
    private static boolean looksLikePersonName(String segment) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        int realWordCount = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (w.length() == 1 && Character.isLetter(w.charAt(0))) continue; // initial
            if (!NAME_TOKEN.matcher(w).matches()) return false;
            if (NON_NAME_TOKENS.contains(w.toUpperCase())) return false;
            realWordCount++;
        }
        return realWordCount >= 2 && realWordCount <= 4;
    }
}
