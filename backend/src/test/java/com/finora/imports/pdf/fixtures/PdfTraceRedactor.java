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

    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}");
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
            "a", "an", "and", "or", "the", "for", "is", "not", "applicable", "yes", "y", "n"));

    public static List<PositionedText> redact(List<PositionedText> runs) {
        Set<String> vocabulary = vocabulary();
        List<PositionedText> out = new ArrayList<>(runs.size());
        for (PositionedText run : runs) {
            out.add(new PositionedText(redactText(run.text(), vocabulary), run.x(), run.y(), run.pageIndex()));
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
                out.append(redactToken(text.substring(start, i), vocabulary));
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
        if (DATE_LIKE.matcher(bare).matches() || AMOUNT_LIKE.matcher(bare).matches()) return token;
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
