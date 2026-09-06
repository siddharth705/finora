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
 * because there is no merchant -- this is a taxonomy answer ("Personal Transfer", see
 * {@code CategorizationService.P2P_CATEGORY}), not a smarter match.
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
 * <p>Three things attack that limitation, in descending order of how much they actually recover:
 *
 * <ol>
 *   <li>{@link #MERCHANT_ACQUIRER_MARKER} -- the big one. A third of these rows settle over a
 *       merchant-acquiring rail, which says "business" no matter whose name is on the payee line.
 *       This is what catches the driver you pay directly instead of in-app.</li>
 *   <li>Small-shop trade words in {@link #BUSINESS_SUFFIX_TOKENS} (SNACKS, DHABA, WINES, ...) --
 *       worth ~12 more rows on the real corpus, at zero measured cost.</li>
 *   <li>Nothing else. Roughly 32 rows (~5%) are a bare truncated trade name and an RRN, with no
 *       VPA and no rail marker at all ("UPI-HANUMAN TEA", "UPI/NEW AMOGHA VEG/&lt;rrn&gt;/UPI"). The
 *       corpus also proves name SHAPE cannot substitute: "MANAS CHAT" reads exactly like a chaat
 *       stall and is a person (18 recurring credits remarked RENT/SAVINGS), while "SUPER BA" and
 *       "DEEP FILLING" are shops. Separating those needs a merchant directory or the user, not
 *       more vocabulary.</li>
 * </ol>
 *
 * <p><b>A methodological warning, because it already cost a shipped regression.</b> Retail
 * vocabulary was once added, then reverted on an A/B over CONSTRUCTED narrations that claimed
 * free-text remarks ("...-Fresh veggies money") would veto genuine transfers wholesale. Re-measured
 * on the real corpus, remarks carry a purpose word only 1.8% of the time and are dominated by one
 * bank's generated "PAYMENT FROM PHONE" boilerplate -- the risk was overstated ~37x and its sign
 * was inverted. Measure this class against the real corpus; invented narrations mislead here.
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
    //
    // The second alternation is FUSED rail codes. Banks render the rail as part of a product code
    // -- UPIINTENT, UPIAB, UPIAR, UPIRET, and IMPS inside SENTIMPS<digits> -- and a \b after the
    // rail word cannot match there, because the next character is a word character. Measured on the
    // real corpus, rows carrying no \b-bounded rail token are only 82 of the 409 that reach
    // "counterparty unknown" but 54.7% of that bucket's VALUE, and 75.7% of its inbound value: a
    // word-boundary technicality was excluding the most valuable rows in the residue.
    //
    // Deliberately an explicit list rather than the general \bUPI[A-Z]+ it would be tempting to
    // write. UPIINTEN(T) is confirmed -- 54 of 54 rows carry a VPA, and 10 of 17 for the longer
    // spelling -- while UPIAB/UPIAR/UPIRET are circumstantial, so a general rule would be
    // extrapolating from five observed codes to every bank's product vocabulary. Same discipline as
    // MERCHANT_ACQUIRER_MARKER: add what the corpus shows, not what the shape suggests.
    private static final Pattern TRANSFER_MARKER =
            Pattern.compile("\\b(UPI|NEFT|IMPS|RTGS)\\b"
                    + "|\\bUPI(?:INTENT?|AB|AR|RET)\\b"
                    + "|SENTIMPS", Pattern.CASE_INSENSITIVE);

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
     * <p>Strictly corporate/trade-entity vocabulary. Everyday nouns stay out even when they sound
     * business-ish, because this set is applied to narrations that can also carry a free-text payer
     * remark -- see the {@code FURNITURE} note below for the one real instance of that failure.
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
            "TELECOM", "COMMUNICATIONS", "AGENCY",
            // Small-shop trade words, re-added on REAL-corpus evidence after an earlier revert.
            //
            // These were removed once because a CONSTRUCTED A/B suggested free-text remarks would
            // veto genuine transfers wholesale ("...-Fresh veggies money"). Re-measured against the
            // actual 29-statement corpus, that risk was overstated by roughly 37x AND inverted:
            // across 673 real P2P-classified rows these words flip exactly 12, and all 12 are
            // genuine businesses -- BALAJI SNACKS, SHREE DATTA SNACKS, BANSI VAISHND DHABA, NATRAJ
            // PROVISION, REGAL WINES, RAVI AUTO CENTR, SNEHA FRESH CHI, DEEP FILLING, SUPER BA --
            // most of them corroborated independently by a merchant-acquirer marker in the same
            // narration. Zero genuine person transfers are lost.
            //
            // FURNITURE is deliberately NOT here: it is the one word with real negative evidence,
            // appearing exactly once in the corpus as a payer's remark on a person-to-person
            // transfer. The rest of the everyday-noun set (SALES, BOOKS, TOYS, COLLECTION, PUMP,
            // TRADING, ENERGY, CEMENT, PAINTS) stays out too -- no corpus occurrences either way,
            // so no evidence to justify the remark risk they carry.
            "SNACKS", "DHABA", "PROVISION", "PROVISIONS", "WINES", "FILLING", "AUTO",
            "FRESH", "SUPER", "DIGITAL",
            // Same re-add, second tier: no corpus occurrences, but structurally incapable of being
            // a payer's remark (nobody annotates a transfer "xerox" or "tyres"), so they carry the
            // upside without FURNITURE's downside.
            "XEROX", "TAILORS", "TYRES", "SPARES", "NURSERY", "PHOTOGRAPHY",
            "PARLOUR", "PARLOR"
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

    /**
     * Markers that the money moved over a MERCHANT-ACQUIRING rail rather than a person's own UPI
     * handle -- the payee holds a business account with a PSP, whatever their display name says.
     *
     * <p>This is the answer to a real product gap: in India an enormous amount of everyday SPENDING
     * settles to what looks like an individual. The canonical case is paying an Uber/Ola driver
     * directly instead of in-app, but it generalises to any small vendor. Without this check those
     * rows are read as "sent money to a person" and filed as Transfer -- a confidently wrong
     * category, which this codebase treats as worse than an honest unknown.
     *
     * <p>Measured on the real 29-statement corpus: <b>227 of 673 P2P-classified rows (33.7%)</b>
     * carry one of these -- PhonePe merchant Q-VPA (96), Paytm {@code PAYTM.S}/{@code PAYTM.D}
     * (58), merchant-UPI IFSC {@code MCHUPI}/{@code PTMUPI}/{@code MERUPI} (64), GPay for Business
     * {@code @okbiz} (23), BharatPe (20), Vyapar (4). Their median value is ~59 rupees, exactly the
     * everyday-spend profile the gap predicts.
     *
     * <p>A hit means "not a person-to-person transfer", so the row falls through to the honest
     * "Other" and its review flag rather than asserting Transfer. It deliberately does NOT try to
     * name the category -- knowing money went to a business is not knowing which business.
     *
     * <p>Excludes PhonePe's general {@code YBLUPI} IFSC and the bare PSP brand names, which carry
     * no merchant/person distinction: both appear on ordinary personal transfers too.
     */
    private static final Pattern MERCHANT_ACQUIRER_MARKER = Pattern.compile(
            "(?i)"
            + "\\bQ\\d{6,}@"                      // PhonePe merchant Q-VPA: Q710750321@ybl
            + "|paytm\\.[sd][a-z0-9]*@"           // Paytm merchant: PAYTM.S25PHA0@pty
            // Merchant-UPI IFSC. The BRANCH half is the signal here, unusually: PSPs route
            // merchant collections through dedicated pseudo-branches whose code spells out what
            // they are, so the bank prefix is the part that varies and is deliberately a wildcard.
            + "|[a-z]{4}0(?:MCHUPI|MERUPI|PTMUPI)"
            + "|@okbiz"                           // Google Pay for Business
            + "|\\bbharatpe\\b"
            + "|\\bvyapar\\."
            // Second wave, mined from the 1,098 rows still landing in "Other" after the first.
            // Two acquirer QR/soundbox families and two payment gateways. A gateway in the
            // narration is as conclusive as a merchant VPA: PayU, Razorpay and Cashfree settle
            // only to onboarded businesses -- an individual cannot collect through one.
            + "|paytmqr"                          // Paytm merchant QR / soundbox
            + "|\\bpayu\\b"
            + "|razorpay|\\brzp\\b"
            + "|cashfree");

    /**
     * Whether this narration settled over a merchant-acquiring rail or a payment gateway.
     *
     * <p>Exposed so {@link CounterpartyClassifier} can reuse the exact same pattern this class
     * vetoes on, rather than keeping a second copy of it. Two copies of a regex this specific WILL
     * drift -- the marker set has already grown twice (the Q-VPA/Paytm/IFSC wave, then PAYTMQR/PayU/
     * Razorpay/Cashfree), and a classifier that missed the second wave would type 232 corpus rows as
     * UNKNOWN while this class correctly treated them as businesses.
     */
    public static boolean hasMerchantAcquirerMarker(String description) {
        return description != null && MERCHANT_ACQUIRER_MARKER.matcher(description).find();
    }

    /**
     * Whether any business-suffix or trade token appears anywhere in the narration.
     *
     * <p>Deliberately simpler than {@link #containsBusinessSignal}: that one discounts the statement
     * issuer's own name before the rail marker, because a veto that fires on "HDFC BANK" in the
     * issuer prefix would suppress every genuine transfer on an HDFC statement. Here the caller is
     * TYPING rather than vetoing, and an issuer name is itself a financial-institution signal, so
     * the discount would throw away the very evidence being looked for.
     */
    public static boolean hasBusinessToken(String description) {
        if (description == null || description.isBlank()) return false;
        for (String token : NON_LETTERS.split(description.toUpperCase())) {
            if (BUSINESS_SUFFIX_TOKENS.contains(token)) return true;
        }
        return false;
    }

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
        if (MERCHANT_ACQUIRER_MARKER.matcher(description).find()) return false;

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
