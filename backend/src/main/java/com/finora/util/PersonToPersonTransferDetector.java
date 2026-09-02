package com.finora.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
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
 * <h2>Known limitation, measured rather than assumed</h2>
 *
 * <p>A 2-4 word brand carrying no corporate suffix ("BURGER SINGH", "CHENNAI SILKS", "AMMA MESS")
 * is structurally indistinguishable from a person's name, and this detector will call it a
 * transfer. An adversarial pass measured roughly 27 of 30 such narrations misfiring. That rate is
 * NOT comparable to the 8-12% bound quoted for the corpus-wide sample below -- it is the rate on a
 * deliberately adversarial population, whereas the corpus-wide figure covers all narrations that
 * reach this code. Both are true, and the honest summary is: on real statements most rows reaching
 * this branch are genuinely P2P, but among businesses that happen to look like people the failure
 * rate is high and cannot be closed by extending a word list.
 *
 * <p>An attempt to close it by adding retail trade-name vocabulary (SALES, DIGITAL, FRESH, AUTO,
 * TOYS, STEEL, ...) was measured and REVERTED: because the veto is global and Indian UPI narrations
 * end with the payer's free-text remark ("...-Fresh veggies money", "...-Toys for kid", "...-Auto"),
 * it removed 12 of 18 genuine detections while closing 1 of 28 misfires. Common English nouns
 * cannot be used as business markers in a field that also carries free text. Only unambiguous
 * corporate/trade-entity words belong in {@link #BUSINESS_SUFFIX_TOKENS}.
 *
 * <p>The residual misfire risk is handled downstream rather than here: this suggestion is emitted
 * at {@code ConfidenceEngine.INITIAL_STRUCTURAL_CONFIDENCE}, deliberately below any plausible
 * auto-apply threshold, and {@code CategorizationService.isUnconfirmedGuess} keeps it out of
 * merchant learning entirely.
 *
 * <p>The logic below is a port of a heuristic validated against the full 1,400-transaction real
 * "Other" population from Finora's bank-statement corpus, spot-checked by hand against 110 items
 * (~8% of the population) for an error bound of roughly 8-12%, split across two disclosed,
 * partially-offsetting biases: a lone first name is conservatively NOT counted as a person, and a
 * multi-word brand with no business-descriptor word can look like one.
 *
 * <p>Deliberately conservative: only fires when an explicit transfer-protocol marker (UPI/NEFT/
 * IMPS/RTGS) is present. A bare person-shaped word with no transfer context is too weak a signal
 * -- it could just as easily be part of a merchant's trade name.
 */
public final class PersonToPersonTransferDetector {

    private PersonToPersonTransferDetector() {}

    // Interbank/UPI transfer-protocol markers -- the ONLY context this detector will ever fire in.
    // Deliberately narrower than PaymentRailTokens.RAIL_TOKENS (which also includes ATM/POS/CHQ,
    // none of which imply a person-to-person transfer -- an ATM withdrawal is never a P2P
    // transfer, so reusing that broader set here would be wrong, not just imprecise).
    //
    // CASE_INSENSITIVE against the ORIGINAL string rather than matching an uppercased copy: the
    // match INDEX is used below to split the narration, and String.toUpperCase can CHANGE LENGTH
    // ('ß' -> "SS", and the ligatures PDFBox emits verbatim, 'ﬁ' -> "FI"), which
    // desynchronises an index taken on the uppercased copy from the original it is applied to --
    // silently mis-slicing, and throwing StringIndexOutOfBoundsException once the shift runs past
    // the end. It also sidesteps toUpperCase's locale sensitivity (a Turkish-locale JVM maps 'i'
    // to a dotted capital and stops matching lowercase rails entirely).
    private static final Pattern TRANSFER_MARKER =
            Pattern.compile("\\b(UPI|NEFT|IMPS|RTGS)\\b", Pattern.CASE_INSENSITIVE);

    // Splits a narration into pure-letter tokens. NOT identical to \b-bounded whole-word matching:
    // it also breaks letters out of alphanumeric runs, so "123CO456" yields the token "CO" where
    // \bCO\b would not match (digits are word characters, so there is no boundary there). That is
    // a deliberate widening in the SAFE direction -- it can only add a business veto, whose failure
    // mode is a missed transfer falling back to "Other" -- and it avoids recompiling one Pattern
    // per vocabulary word on every call, the waste CategoryRules.RULE_PATTERNS already calls out.
    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]+");

    /**
     * Business-entity words, checked across the WHOLE narration: a genuine business name can span
     * several delimiter-separated segments ("SHARMA TRADERS-PVT LTD"), so a per-segment check would
     * miss it.
     *
     * <p>Strictly corporate/trade-entity vocabulary. See this class's doc comment for the measured
     * reason common nouns are excluded, however business-sounding they seem: this set is applied to
     * narrations that also carry a free-text payer remark.
     */
    private static final Set<String> BUSINESS_SUFFIX_TOKENS = Set.of(
            "PVT", "LTD", "LLP", "LIMITED", "PRIVATE", "ENTERPRISES", "STORES", "STORE",
            "SERVICES", "SERVICE", "SOLUTIONS", "TRADERS", "ASSOCIATES", "AGENCIES",
            "INDUSTRIES", "EXPORTS", "IMPORTS", "GROUP", "FOUNDATION", "TRUST", "SOCIETY",
            "HOSPITAL", "CLINIC", "PHARMACY", "SCHOOL", "COLLEGE", "UNIVERSITY", "FINANCE",
            "INSURANCE", "FUND", "COMPANY", "CORP", "CORPORATION", "MART", "SHOP",
            "MANAGEMENT", "ASSET", "NSE", "BSE", "SCHEME", "NGO", "PROJECTS", "SYSTEMS",
            "TECHNOLOGIES", "TECH", "LABS", "CONSULTANCY", "CONSULTING", "VENTURES",
            "CAPITAL", "HOLDINGS", "AND", "CO", "HOUSE", "FOODS", "KITCHEN", "CAFE",
            "HOTEL", "RESTAURANT", "SWEETS", "BAKERY", "DAIRY", "MEDICAL", "MEDICALS",
            "MEDICOS", "CENTRE", "CENTER", "PLAZA", "MALL", "BAZAAR", "BAZAR", "BOUTIQUE",
            "JEWELLERS", "JEWELLERY", "ELECTRONICS", "MOBILES", "MOTORS", "AUTOMOBILES",
            "CONSTRUCTION", "BUILDERS", "PROPERTIES", "REALTY", "INFRA", "INFRASTRUCTURE",
            "ACADEMY", "INSTITUTE", "COACHING", "TUITIONS", "LAUNDRY", "SALON", "PRINTERS",
            "STATIONERY", "HARDWARE", "ELECTRICALS", "TRAVELS", "TOURS", "CARGO",
            "LOGISTICS", "COURIER", "BANK", "NBFC", "AMC", "SUPERMARKET", "DEPARTMENTAL",
            "EMPORIUM", "OPTICALS", "RETAIL", "RETAILS", "DECORATORS", "CATERERS",
            "TELECOM", "COMMUNICATIONS", "AGENCY"
    );

    /**
     * The subset of {@link #BUSINESS_SUFFIX_TOKENS} that also appears in a STATEMENT ISSUER's own
     * name, and so must not veto when it shows up BEFORE the transfer marker.
     *
     * <p>Real narrations prefix the statement owner's own institution: "HDFC BANK LIMITED UPI-...",
     * "KOTAK MAHINDRA BANK LIMITED NEFT-...", "STATE BANK OF INDIA UPI-...". Counted as a business
     * signal, the issuer vetoes a line whose actual counterparty is a person.
     *
     * <p>Exempting only these three words, and only before the marker, is what makes that safe. An
     * earlier fix instead DISCARDED all pre-marker text, which fixed the issuer case and opened a
     * far worse one: a counterparty named before the rail ("RAMESH TRADERS PVT LTD-UPI-...-RAMESH
     * KUMAR GUPTA") lost its veto entirely and was filed as a personal transfer at rule-grade
     * confidence -- inverting the failure direction this class depends on.
     */
    private static final Set<String> ISSUER_NAME_TOKENS = Set.of("BANK", "LIMITED", "LTD");

    // Banking/protocol/channel abbreviations and routine transaction-flow boilerplate -- never
    // part of a person's name, but expected in EVERY narration (every UPI transfer literally
    // contains "UPI"), so these are excluded only PER-WORD inside looksLikePersonName, never as a
    // global veto the way BUSINESS_SUFFIX_TOKENS is.
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

    private static final Pattern SEGMENT_DELIMITERS = Pattern.compile("[\\-/_]+");

    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z]{2,15}");

    /**
     * True when {@code description} structurally looks like a UPI/NEFT/IMPS/RTGS transfer to a
     * named individual rather than a business -- see this class's own doc comment for the
     * evidence, the measured limitation, and the conservative gate this stays behind.
     */
    public static boolean isNamedIndividualTransfer(String description) {
        if (description == null || description.isBlank()) return false;
        if (VPA_BUSINESS_QR.matcher(description).find()) return false;

        Matcher marker = TRANSFER_MARKER.matcher(description);
        if (!marker.find()) return false;

        if (containsBusinessSignal(description, marker.start())) return false;

        // Scanned over the WHOLE description, not just the text after the marker: the counterparty
        // does not reliably follow the rail. This repo's own trace fixtures contain narrations
        // whose rail token is the LAST segment ("<name>/<ref>/IMPS"), and slicing them at the
        // marker left nothing but the rail itself for this loop to read.
        for (String segment : SEGMENT_DELIMITERS.split(description)) {
            if (looksLikePersonName(segment.trim())) return true;
        }
        return false;
    }

    /**
     * True when a business-entity word appears anywhere in the narration as a token, with the
     * statement issuer's own name discounted -- see {@link #ISSUER_NAME_TOKENS}.
     *
     * @param markerStart index of the transfer marker; tokens before it are the issuer/account
     *                    boilerplate region, where BANK/LIMITED/LTD alone do not constitute a
     *                    business signal
     */
    private static boolean containsBusinessSignal(String description, int markerStart) {
        for (String token : NON_LETTERS.split(description.substring(0, markerStart).toUpperCase())) {
            if (BUSINESS_SUFFIX_TOKENS.contains(token) && !ISSUER_NAME_TOKENS.contains(token)) {
                return true;
            }
        }
        for (String token : NON_LETTERS.split(description.substring(markerStart).toUpperCase())) {
            if (BUSINESS_SUFFIX_TOKENS.contains(token)) return true;
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
