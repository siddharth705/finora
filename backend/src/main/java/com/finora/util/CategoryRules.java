package com.finora.util;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword-based auto-categorization rules — the server-side counterpart of the
 * client-side rule engine used in the browser prototype, so behavior stays
 * consistent whichever layer ends up doing the categorization.
 *
 * This is intentionally simple pattern matching, not a trained model. Swapping
 * in a real classifier (or calling out to OpenAI per the PRD's "AI layer") is a
 * drop-in replacement for {@link #suggestCategory} — see CategorizationService.
 */
public final class CategoryRules {

    private CategoryRules() {}

    // Built from an explicit char code rather than the string literal "\\b" -- a single
    // backslash in a Java string literal ("\b") is the escape sequence for the ASCII backspace
    // control character, not a literal backslash, so writing the word-boundary token directly
    // as a string literal is one keystroke away from silently compiling into something that
    // matches nothing. Building it from (char) 0x5C removes that ambiguity entirely.
    private static final String WORD_BOUNDARY = String.valueOf((char) 0x5C) + "b";

    public static final Map<String, List<String>> RULES = new LinkedHashMap<>();
    static {
        RULES.put("Salary", List.of("salary", "payroll", "income tax refund", "stipend"));
        // "housingcom" (Housing.com, printed as one contiguous word on the real statement) added
        // after checking this project's own real bank-statement corpus (docs/superpowers/specs/
        // 2026-09-01-transaction-categorization-design.md §1) -- mapped to Rent on the assumption
        // this is a rent-payment-facilitator narration (see CategoryRulesTest's Housingcom test
        // comment for the reasoning and its caveat). Safe as a bare keyword: word-boundary matching
        // only matches the exact bounded token "housingcom", never as a prefix inside a longer run
        // like "housingcommunity" (guarded by suggestCategory_housingCommunityIsNotMisclassifiedAsRent).
        RULES.put("Rent", List.of("house rent", "rent paid", "rent payment", "monthly rent", "rent due", "landlord", "housing society", "maintenance chg", "housingcom"));
        RULES.put("Groceries", List.of("bigbasket", "blinkit", "zepto", "grofers", "dmart", "grocery", "supermarket"));
        // "asspl" (Amazon Seller Services' actual card-statement abbreviation) and "cinnabon"
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- both real, verified misses,
        // safe as bare keywords: neither is a substring of any other keyword or common English/
        // Indian-banking-narration word, so word-boundary matching has nothing plausible to
        // misfire against.
        // "gokhana" is a workplace-cafeteria ordering platform, and the single highest-frequency
        // unmatched brand in the corpus: 105 rows across 6 of the 29 statements, i.e. multiple
        // distinct people, which is what separates real vocabulary from overfitting to one payer.
        // "tobox" (Tobox Ventures Private Limited, the registered corporate name behind
        // "Gokhana" -- a real narration links them directly: "TOBOX VENTURES PRIVATE LIMITED/
        // GOKHANA.") added after re-checking this project's own real bank-statement corpus for
        // additional vocabulary beyond the 2026-09-01 review (docs/superpowers/plans/2026-09-05-
        // categorization-vocabulary-expansion.md Task 1). Kept as a bare word rather than "tobox
        // ventures" because one real statement truncates the narration to "TOBOX VENT" -- a
        // two-word phrase keyword would miss that form. Safe as a bare keyword: not a substring of,
        // or a container of, any other keyword in this table.
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana", "tobox"));
        // "indian railways" (the national railway institution, named directly rather than
        // through its "irctc" booking portal already above) added after re-checking this
        // project's own real bank-statement corpus for additional vocabulary beyond the
        // 2026-09-01 review (docs/superpowers/plans/2026-09-05-categorization-vocabulary-
        // expansion.md Task 1). Kept as the full two-word phrase, not a bare "indian": a bare
        // keyword would misfire on real "INDIAN CLEARING CORP" settlement narrations seen in the
        // same corpus (guarded by suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport).
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking", "indian railways"));
        RULES.put("Utilities", List.of("electricity", "power bill", "water bill", "gas bill", "broadband", "airtel", "jio", "recharge"));
        // "pureplay" (Pureplay Skin Sciences, a real D2C skincare/personal-care e-commerce brand)
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- a real, verified miss, safe
        // as a bare keyword: not a substring of any other keyword or common English/Indian-banking-
        // narration word, so word-boundary matching has nothing plausible to misfire against.
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl", "pureplay"));
        RULES.put("Health", List.of("pharmacy", "apollo", "medplus", "hospital", "clinic", "netmeds", "1mg"));
        RULES.put("Entertainment", List.of("netflix", "prime video", "hotstar", "spotify", "bookmyshow", "pvr", "inox"));
        // "mutualfunds" is not redundant with "mutual fund": matching is word-boundary over the
        // NORMALIZED description, and normalize() only replaces non-alphanumerics with spaces -- it
        // never splits a run-together word. The unspaced form is what actually appears on real
        // statements (12 rows on the corpus, all previously "Other"), so the spaced keyword could
        // never reach them.
        // "nse mf" (National Stock Exchange's mutual-fund investment platform) added after
        // checking this project's own real bank-statement corpus -- kept as the exact two-word
        // phrase seen on the real narration ("NSE MF"), the same choice already made for
        // "cc payment": matching the full phrase rather than a bare "mf" avoids the false-positive
        // risk a 2-letter fragment would carry.
        RULES.put("Investments", List.of("mutual fund", "mutualfunds", "sip", "zerodha", "groww", "upstox", "nps", "ppf", "demat", "nse mf"));
        RULES.put("Fees/Interest", List.of("annual fee", "late fee", "finance charge", "interest charged", "penalty"));
        // "cc payment" added after checking this project's own real bank-statement corpus (see
        // Shopping/Dining comment above) -- a real BharatBillPay narration ("BPPY CC PAYMENT")
        // abbreviated past what "credit card payment"/"card bill payment" already catch. Safe as a
        // two-word phrase: word-boundary matching means "cc" alone is never checked in isolation.
        //
        // "rtgs" and "self transfer" added per the reconciliation benchmark's post-fix failure
        // analysis (docs/proposals/reconciliation-benchmark/post-fix-failure-analysis.md, item 1a):
        // ReconciliationService's transfer pass reuses this exact list to decide whether a
        // candidate pair looks like a transfer at all (see that pass's own comment), and RTGS is a
        // real, common Indian settlement rail this list was missing entirely. Deliberately NOT
        // adding "wallet" or "auto debit" in the same change -- both were considered and rejected:
        // "auto debit" in particular would misfire on a genuine EMI or insurance auto-debit, which
        // must NOT be recategorized as a transfer (see the same analysis doc's ranking table, and
        // the parked EMI/loan-repayment relationship types this project has already deliberately
        // deferred -- docs/proposals/reconciliation-benchmark/dead-relationship-types-audit.md).
        // "self transfer" is kept as the two-word phrase real UPI apps print (e.g. "UPI-SELF
        // TRANSFER-...") rather than the bare word "self", which is common enough in unrelated
        // narrations that a bare-word match would carry real false-positive risk.
        RULES.put("Transfer", List.of("credit card payment", "card bill payment", "cc payment", "autopay", "neft to", "imps to", "billdesk", "rtgs", "self transfer"));
        // Appended after the original set (see AuthService.DEFAULT_CATEGORIES, which this list
        // now mirrors) rather than interleaved — insertion order is match priority for
        // suggestCategory's first-match-wins loop, and none of these keywords collide with the
        // rules above, so appending can't change any existing categorization.
        // "emi" and "ngo" deliberately excluded as bare keywords here: contains()-based matching
        // means a 3-letter substring hits far more than intended — "emi" is inside "premium"
        // (so an insurance payment would misfire as Loan EMI before Insurance's own rule ever
        // runs) and inside "academic"/"chemistry", and "ngo" is inside "mongo"/"flamingo"/
        // "bingo"/"tango" (so e.g. a "MongoDB" hosting charge would misfire as a donation). The
        // compound phrases below keep the same real-world coverage without the false positives.
        RULES.put("Loan EMI", List.of("loan emi", "emi payment", "emi deduction", "personal loan", "home loan", "car loan", "auto loan"));
        // "pmjjby" (Pradhan Mantri Jeevan Jyoti Bima Yojana, a real Government of India life-
        // insurance scheme) added after checking this project's own real bank-statement corpus --
        // safe as a bare keyword for the same reason "pureplay" above is: a distinctive acronym,
        // not a substring of any other keyword or common narration word.
        RULES.put("Insurance", List.of("insurance", "lic premium", "policybazaar", "premium payment", "pmjjby"));
        // "nwd" (Non-Home-branch Withdrawal, the standard NPCI/bank narration code for an ATM
        // withdrawal at another bank's machine) added after checking this project's own real
        // bank-statement corpus -- the only real ATM row in it ("NWD-416021XXXXXX5853-...") was
        // falling through every keyword above to "Other". Safe as a bare 3-letter keyword despite
        // the "emi"/"ngo" caution below: unlike those two, "nwd" is not a substring of any common
        // English or Indian-banking-narration word, so the word-boundary matching this file already
        // requires (see RULE_PATTERNS below) has nothing plausible to misfire against.
        RULES.put("Cash Withdrawal", List.of("atm withdrawal", "atm wdl", "cash withdrawal", "cash wdl", "nwd"));
        RULES.put("Travel", List.of("makemytrip", "goibibo", "yatra", "airbnb", "oyo", "indigo", "spicejet", "vistara", "hotel booking"));
        RULES.put("Subscriptions", List.of("google one", "icloud", "adobe", "microsoft 365", "linkedin premium"));
        RULES.put("Education", List.of("udemy", "coursera", "byjus", "tuition fee", "school fee", "college fee"));
        RULES.put("Gifts & Donations", List.of("donation", "charity", "ngo donation", "gift"));
    }

    /**
     * Every keyword this table matches on, flattened and unordered.
     *
     * <p>Exposed for {@link MerchantIdentityLookup}, which needs the VOCABULARY without the
     * category mapping: "Amazon is a known merchant" is an identity fact, while "Amazon means
     * Shopping" is a categorization fact, and only the first one belongs to a counterparty
     * decision. Handing out the terms rather than a second copy of them is deliberate -- the
     * duplicated-marker-set problem is already documented on
     * {@code PersonToPersonTransferDetector.hasMerchantAcquirerMarker}, and this table has grown
     * three times in the last week alone.
     */
    public static Set<String> allKeywords() {
        return RULES.values().stream().flatMap(List::stream).collect(Collectors.toUnmodifiableSet());
    }

    public static String normalize(String desc) {
        if (desc == null) return "";
        return desc.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Strips numeric reference codes to surface a clean merchant token, e.g. "SWIGGY*ORDR9182" -> "swiggy". */
    public static String extractMerchant(String desc) {
        String n = normalize(desc).replaceAll("\\b[a-z]*\\d{4,}[a-z]*\\b", " ").replaceAll("\\s+", " ").trim();
        String[] tokens = n.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, tokens.length); i++) {
            // CodeQL (java/misleading-indentation), 2026-09-04: braced explicitly -- the inner,
            // brace-less `if (sb.length() > 0) sb.append(' ');` was always correctly scoped to
            // just that one statement by Java's own grammar (sb.append(tokens[i]) always ran
            // unconditionally), but reads as the classic dangling-if footgun on a quick skim.
            if (tokens[i].length() > 1) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i]);
            }
        }
        return sb.length() > 0 ? sb.toString() : (n.isEmpty() ? "unknown" : n);
    }

    /**
     * {@link #extractMerchant} reduced further for the per-transaction "who was this with" label
     * (Transaction.merchant) the UI shows and looks up on Logo.dev by name.
     *
     * extractMerchant()'s raw output must stay untouched for its other callers, which reduce both
     * sides of a comparison the same way (see MerchantNormalizationEngine.firstSignificantToken,
     * which already skips {@link PaymentRailTokens} for that grouping key) -- changing what
     * extractMerchant itself returns for a bare rail narration would change that grouping key too
     * and defeat the deliberate null-means-"don't group" behavior documented there.
     *
     * A display label that survives as nothing but a rail word ("upi", "ach") is actively
     * misleading rather than merely uninformative: MerchantLogo looks it up on Logo.dev by name
     * and gets back a real, unrelated company that happens to trademark that word. Null lets
     * Transaction.merchant stay unset, and the ledger UI already falls back to the transaction's
     * own description when merchant is empty.
     */
    public static String extractMerchantLabel(String desc) {
        String merchant = extractMerchant(desc);
        for (String token : merchant.split(" ")) {
            if (!PaymentRailTokens.isRailToken(token)) return merchant;
        }
        return null;
    }

    // Compiled once at class-load time rather than per suggestCategory() call -- this runs once
    // per CSV row during statement import, so recompiling the same ~90 patterns on every row
    // would be pure waste.
    //
    // Word-boundary matching (not String.contains) is required here: naive substring matching
    // means "rent" matches inside "current" (a phrase that shows up in nearly every Indian bank
    // statement line, e.g. "UPI-CURRENT A/C"), and "ola" (Transport) matches inside "cola" (as
    // in a Coca-Cola purchase on a grocery/dining line). Both are real, evidenced false-positive
    // risks, not theoretical -- fixed once, systemically, for every keyword at once rather than
    // patched keyword-by-keyword.
    // Reconciliation benchmark, investment-transfer word-boundary finding (docs/proposals/
    // reconciliation-benchmark/corpus-frequency-analysis.md, "Finding F"): a broker's aggregator ID
    // gets fused into one token by some payment gateways with no separating character at all --
    // e.g. "UPI-ICCLGROWWPAY-<ref>-BSE", where "groww" is preceded by "l" and followed by "p" with
    // no boundary on either side. Measured directly against this project's own real corpus: 8 of 19
    // real transaction lines mentioning "Groww" carried it ONLY in this fused form -- 42%, not a
    // theoretical edge case.
    //
    // This is deliberately NOT a blanket loosening of word-boundary matching -- the comment on
    // RULE_PATTERNS above exists precisely because that already burned this codebase twice ("rent"
    // inside "current", "ola" inside "cola"). The three brand names below are exempted individually,
    // and only because each is long and phonetically distinctive enough that an accidental
    // substring collision inside an unrelated English or Indian-banking-narration word is not a
    // realistic risk the way it would be for a short or generic keyword. Every other Investments
    // keyword ("sip", "nps", "ppf", "mutualfunds", "demat", "nse mf") stays word-boundary-matched:
    // "sip" in particular is exactly the short, common-substring case ("gossip", "sipping") this
    // exemption must never be widened to cover without the same kind of real-corpus evidence
    // gathered for these three first.
    private static final Set<String> FUSION_TOLERANT_KEYWORDS = Set.of("groww", "zerodha", "upstox");

    private static final Map<String, List<Pattern>> RULE_PATTERNS = new LinkedHashMap<>();
    static {
        for (var entry : RULES.entrySet()) {
            List<Pattern> patterns = entry.getValue().stream()
                    .map(w -> FUSION_TOLERANT_KEYWORDS.contains(w)
                            ? Pattern.compile(Pattern.quote(w))
                            : Pattern.compile(WORD_BOUNDARY + Pattern.quote(w) + WORD_BOUNDARY))
                    .toList();
            RULE_PATTERNS.put(entry.getKey(), patterns);
        }
    }

    /** Returns a rule-based category guess, or "Other" if nothing matches. Callers should check
     *  a per-user learned-mapping table (MerchantCategoryMap) BEFORE falling back to this. */
    public static String suggestCategory(String description) {
        String norm = normalize(description);
        for (var entry : RULE_PATTERNS.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(norm).find()) return entry.getKey();
            }
        }
        return "Other";
    }
}
