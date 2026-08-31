package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PositionedText;
import com.finora.util.BankRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips a captured {@link PdfTrace} of personal data while leaving the document's structure
 * exactly as it was.
 *
 * The rule is an ALLOWLIST, not a denylist: a token is kept only if it is recognisably part of a
 * statement's furniture -- a column header, a metadata label, a date, an amount, a bank's name.
 * Everything else is replaced character-for-character. That direction is the whole point. A
 * denylist of "things that look like PII" fails open on the case nobody anticipated, and the
 * failure mode is a customer's name in a public repository. This fails closed: an unrecognised
 * token is redacted even when it was harmless, and the cost of that is a slightly less readable
 * fixture rather than a privacy incident.
 *
 * Redaction preserves LENGTH and CHARACTER CLASS (letter -&gt; X, digit -&gt; 9, punctuation kept),
 * because length is structure here: it is what makes a narration wrap onto a second line, what
 * decides where a run's x-extent ends, and therefore what reproduces the bug.
 *
 * Dates and amounts are deliberately preserved. Once names, account numbers, addresses, emails and
 * narration text are gone, a bare "24,462.00" or "10/07/2026" identifies nobody -- and keeping them
 * is what allows a trace to regression-test date parsing, running balances and (once it exists)
 * reconciliation against the statement's own arithmetic. Redacting them would leave a fixture that
 * can only ever test header detection.
 */
public final class PdfTraceRedactor {

    private PdfTraceRedactor() {}

    /**
     * Bumped by hand when the redaction ALGORITHM changes -- a new pattern, a changed masking rule,
     * a different tokenisation. Not for allowlist edits, which {@link #allowlistFingerprint()}
     * tracks automatically.
     *
     * Recorded into every captured trace so a trace states which redactor produced it. Without it,
     * "was this trace captured before or after we fixed X" is answerable only by reading commit
     * dates.
     */
    public static final int REDACTOR_VERSION = 2;

    /**
     * A short hash of the effective allowlist, recomputed from the live vocabulary every call.
     *
     * This is the mechanism that makes stale-trace detection automatic instead of a process someone
     * has to remember. A trace records the fingerprint it was captured under; when the allowlist
     * changes, every committed trace's recorded fingerprint stops matching the current one, and
     * {@code TraceCorpusHealthTest} names exactly which traces are affected.
     *
     * The motivating incident: the allowlist had no deposit vocabulary, so three captured traces
     * had "Maturity Date" masked to "Xxxxxxxx Date" -- the precise headers product classification
     * reads, removed from the fixtures meant to regression-test reading them. The allowlist was
     * then fixed, and nothing anywhere connected that fix to the traces it invalidated. A committed
     * trace cannot be un-redacted, so the damage is permanent and was only found by dumping a
     * fixture by hand and noticing the words were wrong.
     *
     * Derived from the vocabulary rather than hand-maintained precisely so it cannot be forgotten:
     * editing STRUCTURAL_WORDS changes it whether or not anyone thought about the traces.
     */
    public static String allowlistFingerprint() {
        StringBuilder canonical = new StringBuilder();
        // Sorted so the fingerprint depends on the allowlist's CONTENT, not on the order words
        // happen to be declared in -- reordering the list is not a reason to invalidate the corpus.
        new java.util.TreeSet<>(vocabulary()).forEach(word -> canonical.append(word).append('\n'));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 8).toUpperCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}");
    // A real Kotak Mahindra Bank credit-card statement states every pre-table metadata date
    // ("16-Feb-2026", "02-Apr-2026") in this named-month shape rather than DATE_LIKE's all-numeric
    // one. Without this, the day and year survive as bare digit tokens (neither DATE_LIKE nor
    // AMOUNT_LIKE matches a lone "16" or "2026" with no decimal fraction) but the month name does
    // not -- it is not in STRUCTURAL_WORDS -- so the whole date silently degrades to "99-Xxx-9999"
    // and a trace meant to regression-test parsing this exact format carries no real date to parse.
    // Matched and preserved WHOLE, the same "dates are structure, not identity" reasoning DATE_LIKE
    // above already applies.
    private static final Pattern NAMED_MONTH_DATE_LIKE = Pattern.compile(
            "\\d{1,2}-(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-\\d{2,4}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_LIKE = Pattern.compile(
            "\\(?-?[\\d,]+\\.\\d{1,2}\\)?");
    /** Account numbers, card numbers and payment references. Never preserved, whatever else the
     *  token looks like -- this check runs before the amount/date ones. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{8,}");
    /** An IFSC is split rather than kept or dropped whole: the 4-letter prefix identifies the BANK
     *  and is the signal bank detection is built on, while the 6-character branch code identifies
     *  a specific branch and is the part worth removing. Masking the whole token would leave a
     *  trace unable to regression-test the detection path at all. */
    private static final Pattern IFSC = Pattern.compile("([A-Z]{4})0[A-Z0-9]{6}");

    /**
     * Statement furniture the pipeline itself keys on. Losing any of these would stop a trace from
     * exercising the very code path it was captured to cover -- redact "Narration" and header
     * detection has nothing to detect.
     */
    private static final Set<String> STRUCTURAL_WORDS = new LinkedHashSet<>(List.of(
            // column headers and their variants
            "date", "txn", "transaction", "value", "dt", "narration", "description", "particulars",
            "remarks", "details", "withdrawal", "withdrawals", "deposit", "deposits", "debit",
            "debits", "credit", "credits", "amount", "amt", "balance", "closing", "opening",
            "chq", "cheque", "ref", "no", "reference", "dr", "cr", "count",
            // metadata labels
            "statement", "account", "accounts", "savings", "current", "summary", "total", "from",
            "to", "as", "on", "period", "ifsc", "micr", "rtgs", "neft", "branch", "code", "bank",
            "limited", "ltd", "type", "currency", "inr", "nomination", "registered", "customer",
            "name", "id", "email", "address", "city", "state", "country", "phone", "contact",
            "holder", "holders", "joint", "nominee", "status", "open", "limit", "od", "sweep",
            "fd", "hold", "unclear", "withdrawable", "available", "card", "number", "due",
            "minimum", "payment", "page", "of", "continued", "brought", "carried", "forward",
            "cust", "cif", "ccy",
            // Financial-product vocabulary. Added after a captured trace was found to have redacted
            // "Maturity Date" to "Xxxxxxxx Date" and "Deposit(Mnth)" to "Deposit(Xxxx)" -- the exact
            // column headers ProductEvidenceCollector classifies on, gone from the fixture that was
            // supposed to regression-test classifying them. None of these words identify anybody;
            // they are statement furniture in precisely the sense this allowlist exists to keep.
            // A committed trace cannot be un-redacted, so traces captured before this are missing
            // this vocabulary permanently and need re-capturing to exercise product classification.
            "maturity", "matures", "principal", "interest", "rate", "roi", "tenure", "tenor",
            "installment", "instalment", "installments", "instalments", "recurring", "term",
            "monthly", "mnth", "frequency", "paid", "due", "start", "end", "renewal", "scheme",
            "emi", "loan", "outstanding", "disbursed", "repayment", "overdraft", "folio", "nav",
            "units", "isin", "demat", "ppf", "epf", "nps", "uan", "pran", "rd", "running",
            // Transaction-instrument prefixes. Structural (they classify the row), and they carry
            // no identity on their own -- the counterparty that follows them is what gets masked.
            "upi", "neft", "imps", "rtgs", "ach", "atm", "pos", "ecs", "nach", "int", "chgs",
            // generic connective words that carry no identity on their own
            "a", "an", "and", "or", "the", "for", "is", "not", "applicable", "yes", "y", "n",
            // Credit-card ledger category headers and payment-instruction furniture. Added after a
            // real Kotak Mahindra Bank credit-card statement was found to have its own
            // "Payments and Other Credits"/"Primary Card Transactions"/"Retail Purchases and Cash
            // Transactions" section-header vocabulary masked away by capture -- the exact bare,
            // dateless label lines the row-continuation merge needs to recognize and drop rather
            // than sweep into an adjacent transaction's description, gone from the fixture meant to
            // regression-test that. Same for the document's "Remember to pay by <date>"/"Pay your
            // Credit card bills using the following:" sentences, neither a "Due Date" label nor any
            // already-covered trailing-content marker. None of these identify anybody.
            "payments", "other", "primary", "transactions", "retail", "purchases", "cash",
            "remember", "pay", "by", "your", "bills", "using", "following",
            // "Rs" -- the Indian Rupee abbreviation this same statement's own amount-column header
            // uses ("Amount (Rs.)R", the trailing "R" its own separate text run), distinct from
            // "inr" above (already allowlisted).
            "rs",
            // ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED's own trigger vocabulary. A real Central Bank of
            // India export and a real PNB ONE export each close with a regulatory-boilerplate sentence
            // ("Unless a constituent notifies the Bank immediately of any discrepancy...") that the
            // trigger pattern itself matches on -- masking any of these words away leaves a captured
            // trace unable to exercise the trigger it exists to protect. None of these identify
            // anybody; it is the same fixed regulatory disclosure on both documents.
            "unless", "constituent", "notifies", "immediately", "any", "discrepancy"));

    /**
     * A bare single letter immediately trailing an already-preserved currency marker WITHIN THE
     * SAME run -- e.g. this same real Kotak Mahindra Bank statement's "Amount" column header, whose
     * own text is one single {@code PositionedText} run reading {@code "(Rs.)R"} (verified against
     * the real PDF's own positioned output; the closing paren is what splits it into the two tokens
     * {@code "Rs."} and {@code "R"} that {@link #redactText} processes). The trailing "R" is a
     * rendering artifact of the rupee glyph, not a word this class's word-level allowlist could
     * ever recognize on its own -- {@link #STRUCTURAL_WORDS} above is deliberately word-shaped.
     *
     * <p><b>Why masking it broke a whole column, not just cosmetics.</b> {@link #redact} zeroes the
     * WIDTH of any run whose redacted text differs from the original at all (see that method's own
     * doc comment) -- correct for a run that is genuinely hidden, but this run is 5/6ths real text
     * ("(Rs.)") and one masked letter, and the whole run's width -- this header cell's own trailing
     * edge -- paid for that one letter. {@code PdfTableLocator}'s RIGHT_ALIGNED_AMOUNTS correction
     * -- the mechanism that reassigns a right-aligned numeric value from its naive nearest-header
     * bucket to the column its printed position actually lines up with -- is itself guarded on
     * {@code width() > 0} (see that capability's own real-corpus history in
     * CapabilityCoverageService), so it silently declined to run for this column at all. Every one
     * of this section's 20 real purchase rows landed under a phantom "(Rs.)" column instead of
     * "Amount" -- the one name {@code TransactionNormalizer.AMOUNT_HINTS} actually recognizes -- and
     * staged as unparseable "no column recognized as an amount or balance" in the trace-driven
     * regression suite, even though the real (unredacted) document parses every one of them
     * correctly. A redaction-fixture artifact, not a production defect -- confirmed by running the
     * real PDF bytes (not the trace) through the identical pipeline.
     *
     * <p>Deliberately its own narrow check rather than a bare "r" added to STRUCTURAL_WORDS: an
     * unconditional single-letter allowance would preserve a stray initial anywhere in a document (a
     * genuine, if low-information, piece of PII), where this scopes the exception to the one real
     * shape that motivates it -- immediately following a currency marker's own closing paren, not
     * any lone letter anywhere.
     */
    private static final java.util.regex.Pattern TRAILING_CURRENCY_MARKER_LETTER =
            Pattern.compile("^[A-Za-z]$");

    private static boolean isTrailingCurrencyMarkerLetter(String token, StringBuilder alreadyBuilt) {
        if (!TRAILING_CURRENCY_MARKER_LETTER.matcher(token).matches()) return false;
        String precedingLower = alreadyBuilt.toString().toLowerCase(Locale.ROOT);
        return precedingLower.endsWith("rs.)") || precedingLower.endsWith("rs)");
    }

    /**
     * Redacts every run, preserving the measured width of runs redaction left byte-identical and
     * dropping it (to 0) for every run it changed.
     *
     * <p>The discriminator is exact rather than heuristic: redaction is purely character-substituting
     * ({@link #mask}, which preserves length and every non-alphanumeric character), so
     * {@code redacted.equals(original)} is true if and only if nothing about the run was hidden.
     *
     * <p><b>Why width is kept at all.</b> {@code PdfTableLocator}'s right-edge correction
     * (RIGHT_ALIGNED_AMOUNTS) and {@code StatementSummaryExtractor#valueUnder} are guarded on
     * {@code width() > 0}; with every width zeroed, no trace at any version could reach them, and the
     * corpus was structurally blind to the exact class of defect it exists to catch — a short
     * right-aligned amount bucketing into the wrong column.
     *
     * <p><b>Why only for unmasked runs.</b> An unmasked run's width is a deterministic function of
     * text this same file already publishes verbatim, so it discloses nothing new. A masked run's
     * width would be a real-valued observation about hidden characters — for alphabetic tokens it
     * constrains the letter multiset (~5–6 bits) — and nothing in the pipeline reads it: every width
     * consumer takes either a pure number (preserved by {@code AMOUNT_LIKE}) or a structural header
     * label (preserved by {@code STRUCTURAL_WORDS}). So the privacy cost buys no capability, and is
     * declined. It also keeps the artefact internally consistent: a width in a trace is always the
     * true width of the text printed next to it.
     */
    public static List<PositionedText> redact(List<PositionedText> runs) {
        Set<String> vocabulary = vocabulary();
        List<PositionedText> out = new ArrayList<>(runs.size());
        for (PositionedText run : runs) {
            String redacted = redactText(run.text(), vocabulary);
            boolean unmasked = java.util.Objects.equals(redacted, run.text());
            out.add(unmasked
                    ? new PositionedText(redacted, run.x(), run.y(), run.pageIndex(), run.width())
                    : new PositionedText(redacted, run.x(), run.y(), run.pageIndex()));
        }
        return out;
    }

    /** Structural words plus every bank's own identifiers, taken from {@link BankRegistry} so this
     *  never drifts as banks are added. A bank's name is not identifying once the account holder's
     *  is gone, and preserving it is what lets a trace regression-test bank detection. */
    private static Set<String> vocabulary() {
        Set<String> vocabulary = new LinkedHashSet<>(STRUCTURAL_WORDS);
        for (BankRegistry.BankInfo bank : BankRegistry.all()) {
            vocabulary.add(bank.id().toLowerCase(Locale.ROOT));
            addWords(vocabulary, bank.officialName());
            addWords(vocabulary, bank.shortName());
        }
        return vocabulary;
    }

    private static void addWords(Set<String> vocabulary, String phrase) {
        if (phrase == null) return;
        for (String word : phrase.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isBlank()) vocabulary.add(word);
        }
    }

    static String redactText(String text, Set<String> vocabulary) {
        if (text == null || text.isBlank()) return text;
        StringBuilder out = new StringBuilder(text.length());
        // Split keeping the separators, so every space and its exact position survives -- the
        // whitespace between two runs is part of the layout being reproduced.
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < text.length() && isTokenChar(text.charAt(i))) i++;
                String token = text.substring(start, i);
                // See TRAILING_CURRENCY_MARKER_LETTER's own doc comment -- checked ahead of the
                // ordinary vocabulary/date/amount rules below, against what THIS SAME redaction
                // pass has already appended for this run, not a separate lookup.
                out.append(isTrailingCurrencyMarkerLetter(token, out) ? token : redactToken(token, vocabulary));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A token runs across '.' and ',' so that "24,462.00" and "10/07/2026" are judged whole rather
     *  than as the fragments "24", "462" and "00". */
    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == ',' || c == '/' || c == '-';
    }

    private static String redactToken(String token, Set<String> vocabulary) {
        String bare = token.replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}]+$", "");
        if (bare.isEmpty()) return token;

        var ifsc = IFSC.matcher(bare);
        if (ifsc.matches()) return ifsc.group(1) + "0XXXXXX";

        // Order matters: a long digit run is an account or reference number even when it would
        // otherwise pass as an amount, so it is checked first and always redacted.
        if (LONG_DIGIT_RUN.matcher(bare).find()) return mask(token);
        if (DATE_LIKE.matcher(bare).matches() || AMOUNT_LIKE.matcher(bare).matches()
                || NAMED_MONTH_DATE_LIKE.matcher(bare).matches()) return token;
        if (vocabulary.contains(bare.toLowerCase(Locale.ROOT))) return token;

        // '/' and '-' are part of a token so that dates and amounts are judged whole, which also
        // glues compound labels together: "RTGS/NEFT" and "UPI-SOMEONE" arrive as single tokens and
        // would be masked entirely for not appearing in the vocabulary -- taking the "RTGS/NEFT
        // IFSC" label with them, and with it the trace's ability to test bank detection. Once the
        // whole token has failed every rule above, judge its parts individually.
        if (bare.indexOf('/') >= 0 || bare.indexOf('-') >= 0) {
            StringBuilder rejoined = new StringBuilder();
            int start = 0;
            for (int i = 0; i <= token.length(); i++) {
                if (i == token.length() || token.charAt(i) == '/' || token.charAt(i) == '-') {
                    rejoined.append(redactToken(token.substring(start, i), vocabulary));
                    if (i < token.length()) rejoined.append(token.charAt(i));
                    start = i + 1;
                }
            }
            return rejoined.toString();
        }
        return mask(token);
    }

    private static String mask(String token) {
        StringBuilder masked = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            if (Character.isDigit(c)) masked.append('9');
            else if (Character.isLetter(c)) masked.append(Character.isUpperCase(c) ? 'X' : 'x');
            else masked.append(c);
        }
        return masked.toString();
    }
}
