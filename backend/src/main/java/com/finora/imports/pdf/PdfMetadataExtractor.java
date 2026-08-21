package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts account-level fields from the text that appears ABOVE the transaction table --
 * account holder, account number, branch, IFSC, statement period. This is genuinely PDF-specific
 * (unlike table-row extraction, which reuses StatementValidator/TransactionNormalizer directly):
 * CSV's account-level signals come from table COLUMNS (StatementValidator.scanRow scans each
 * row for an "account holder"-ish column), but a real bank statement PDF's header fields appear
 * as free-standing "Label : Value" lines before the table, not as columns -- there's no existing
 * component that already does this.
 *
 * Every field is nullable, and genuinely null when the label wasn't found -- same discipline
 * ImportDto.DetectedAccountInfo's own doc comment establishes for CSV: nothing here is guessed
 * just to fill a field.
 */
@Component
public class PdfMetadataExtractor {

    // Case-insensitive, tolerant of a colon with surrounding whitespace, or just whitespace with
    // no colon (some statement generators use "Account Number    000123456789" with no colon at
    // all) -- (?:...)? makes the colon itself optional while still requiring some separation.
    // "Customer Name" added as a synonym label: verified against a real PNB ONE statement, whose
    // "Customer Details" panel labels the account holder "Customer Name:" rather than any
    // "Account Holder" variant -- a genuinely different real phrase for the same field, not a
    // formatting quirk of an already-covered one.
    //
    // Bare "Name" added as a further synonym: verified against a real Canara Bank e-passbook,
    // whose account-details panel labels the holder with the single word "Name" ("Name MANAS
    // CHATURVEDI", no colon) -- not "Account Holder"/"Customer Name" at all. The trailing "\b" is
    // load-bearing specifically for this alternative: without it, "Named"/"Nameplate"/"Namely"
    // (an unrelated word that merely starts with "name") would also match, since labelPattern's
    // own "\s*:?\s*" allows zero separator before the captured value -- "Account Holder"/
    // "Customer Name" are unambiguous enough as multi-word phrases not to need the same guard.
    private static final Pattern ACCOUNT_HOLDER = labelPattern("(?:Account Holder(?: Name)?|Customer Name|Name\\b)");
    private static final Pattern ACCOUNT_NUMBER = labelPattern("Account Number");
    // Bug fix: verified against a real Union Bank of India statement -- its "Branch Address" line
    // is a two-column SECTION HEADER ("Branch Address" | "Statement Details" side by side, same
    // pattern as an earlier "Your Details" | "Account Details" header higher up the page), not a
    // genuine "Branch: <name>" field -- without the negative lookahead, the bare "Branch" match
    // consumed "Address Statement Details" as if it were the branch name.
    private static final Pattern BRANCH = labelPattern("Branch(?: Name)?(?!\\s*Address)");
    private static final Pattern IFSC = labelPattern("IFSC(?: Code)?");
    private static final Pattern STATEMENT_PERIOD = labelPattern("Statement Period");
    private static final Pattern CREDIT_LIMIT = labelPattern("(?:Total )?Credit Limit(?: \\(Including Cash\\))?");
    private static final Pattern PAYMENT_DUE_DATE = labelPattern("(?:Payment )?Due Date");

    // GRID_METADATA_TRAILING_LABEL: a genuine second real-world grid-metadata shape, distinct from
    // GRID_DUE_DATE_LABEL's "label row, then a later value row" layout -- here the VALUE comes
    // BEFORE its label on the very same line (e.g. "100200300400599 Account Number", "ABCD0123456
    // IFSC" -- genericized per the Synthetic Fixture Policy; see the engineering principles doc),
    // the reverse of every "Label: Value" pattern above. Verified against a real Union Bank of
    // India statement: its account-details panel renders as a two-column grid where each row is
    // "value label" rather than "label value", and ACCOUNT_HOLDER/ACCOUNT_NUMBER/IFSC above never
    // match this layout at all (none of their lines start with the label).
    //
    // Account holder name specifically: this statement's "Name" field wraps its own value across
    // several lines *before* the "Name" label itself appears (a still-harder shape not attempted
    // here -- see the engineering principles doc's LEADING_NARRATION_CONTINUATION entry for the
    // same underlying "value precedes its label across multiple lines" difficulty in a different
    // part of this pipeline). The grid's OTHER column has a cleaner "Account Name" field on one
    // line, which this uses instead -- a real, if less complete (no honorific), holder name is a
    // better outcome than none. Capped at 3 space-separated capitalized words specifically so a
    // preceding, unrelated address fragment ("...3,BEHIND  KAVITA RAMESH DESAI Account Name")
    // doesn't get swept into the captured name -- verified this cap is what correctly excludes
    // "BEHIND" (itself capitalized) while still capturing the full 3-word name.
    // Bug fix: the leading (?i) applied to the WHOLE pattern, including the [A-Z] the doc comment
    // above depends on to reject an unrelated capitalized fragment -- Java's CASE_INSENSITIVE flag
    // makes [A-Z] match lowercase letters too, so the capitalization requirement this comment
    // describes was never actually enforced. A line ending in the label text (case-insensitively,
    // where casing genuinely does vary across banks) but preceded only by lowercase words -- e.g.
    // "please update your account name" -- would have had those lowercase words captured as if
    // they were a real name, the identical defect LEADING_NAME_LINE below had (see that pattern's
    // own doc comment for the real ICICI statement that surfaced it there). (?i:...) scopes
    // case-insensitivity to just the label text; the captured name portion is case-sensitive
    // again, exactly as the doc comment above always intended.
    private static final Pattern ACCOUNT_NAME_TRAILING_LABEL =
            Pattern.compile("([A-Z][A-Za-z]*(?:\\s+[A-Z][A-Za-z]*){0,2})\\s+(?i:Account\\s*Name)\\s*$");
    private static final Pattern ACCOUNT_NUMBER_TRAILING_LABEL =
            Pattern.compile("(?i)^(\\d{6,20})\\s+Account\\s*Number\\s*$");

    // CARD_NUMBER_LABEL: the label vocabulary a real credit-card statement's own masked-number
    // field actually uses -- verified against real HDFC and Kotak statements, neither of which
    // ever says "Account Number" at all (a card issuer speaks of a CARD number, even though it
    // plays the same identifying role ACCOUNT_NUMBER above already looks for). "Account Number"
    // is included here too so it gets the same leading/trailing-text tolerance the patterns below
    // add -- this is purely additive: ACCOUNT_NUMBER/ACCOUNT_NUMBER_TRAILING_LABEL above are
    // untouched and still run first, so no currently-passing document's behavior changes.
    //
    // Phase 1C.1: also the anchor for a genuine multi-line grid fallback (see this label's
    // same-line-anywhere usage in extract() below) -- verified against a real SBI credit-card
    // statement, whose "Credit Card Number" label sits alone on its own line, with the masked
    // value on the very next one.
    private static final String CARD_NUMBER_LABEL_SRC =
            "(?:(?:Primary\\s+)?(?:Credit\\s+)?Card\\s*(?:No\\.?|Number)|Account\\s*Number)";
    private static final Pattern CARD_NUMBER_LABEL = Pattern.compile("(?i)" + CARD_NUMBER_LABEL_SRC);

    // CARD_NUMBER_VALUE: a card/account number exactly as a real statement prints it -- either the
    // bank's own masked form (X/x/* mask characters interleaved with visible digit groups, e.g.
    // "XXXX XXXX XXXX 1234", "1234********5678") or a genuine unmasked digit run. Deliberately a
    // loose shape here (any 2+ run of digit/mask characters, optionally space/hyphen-grouped) --
    // the real filtering is looksLikeCardOrAccountNumber below, which checks total length and
    // digit count afterward. Keeping that check separate from the regex, rather than trying to
    // express "6-20 characters, at least 2 real digits, ends in a digit" as one pattern, is what
    // keeps this simple enough to verify by eye and to test.
    private static final String CARD_NUMBER_VALUE_SRC = "[\\dXx*]{2,}(?:[\\s-][\\dXx*]{2,})*";
    private static final Pattern CARD_NUMBER_VALUE = Pattern.compile(CARD_NUMBER_VALUE_SRC);

    // CARD_NUMBER_TRAILING_LABEL: the same "value before its label" shape as
    // ACCOUNT_NUMBER_TRAILING_LABEL, but tolerant of text AFTER the label too -- verified against
    // a real HDFC credit-card statement, whose line reads "<value> Credit Card No. <HOLDER NAME>":
    // the holder's name trails the label on the same line, so ACCOUNT_NUMBER_TRAILING_LABEL's own
    // \s*$ end-anchor can never match here. Same class of fix as GRID_DUE_DATE_LABEL's "ends with"
    // -> "contains" widening in Phase 1A -- a new pattern, not a change to the existing one.
    private static final Pattern CARD_NUMBER_TRAILING_LABEL = Pattern.compile(
            "(?i)^(" + CARD_NUMBER_VALUE_SRC + ")\\s+" + CARD_NUMBER_LABEL_SRC + "\\b");
    private static final Pattern STATEMENT_PERIOD_TRAILING_LABEL =
            Pattern.compile("(?i)^(.+?)\\s+Statement\\s*Period\\s*$");
    // IFSC codes have a fixed, distinctive shape (4 letters, a literal 0, 6 more alphanumerics) --
    // reliable enough to find directly by content, independent of any label at all, which sidesteps
    // needing to handle this statement's IFSC line being merged with an unrelated Email field on
    // the same extracted line ("...@GMAIL.COM Email id ABCD0123456 IFSC").
    private static final Pattern IFSC_SHAPE = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");

    // CARD_ENDING_DIGITS: a genuinely different identity shape from every "Account Number" pattern
    // above -- a credit-card statement doesn't label an "Account Number" field at all; it states the
    // card's last 4 digits inside an ordinary sentence ("Statement for your credit card ending with
    // <4 digits>"), verified against a real AU Small Finance Bank credit-card statement. Only the
    // last 4 digits are ever known this way -- there is no full number anywhere on the page to
    // mask -- so this always builds the masked identity directly rather than routing through
    // CsvParser.maskAccountNumber, which would return a 4-digit input UNMASKED (see its own
    // digits.length() <= 4 branch); accountNumberFullForHashingOnly is deliberately left unset here,
    // never guessed from a value this codebase has genuinely never seen.
    //
    // Deliberately requires the literal phrase "credit card", not bare "card": this scans the
    // WHOLE document's auxiliary text, and the same guarded-first-match discipline every field in
    // this class already follows means whichever mention is found FIRST wins permanently. A
    // savings/current-account statement that references a linked DEBIT card in passing ("your
    // debit card ending in 1234...") earlier in the document than its own real Account Number
    // field would otherwise pin the identity to the wrong card's digits. AU's own real phrasing
    // ("...your credit card ending with...") already satisfies this narrower match.
    private static final Pattern CARD_ENDING_DIGITS =
            Pattern.compile("(?i)credit\\s+card\\s+ending\\s+(?:with|in)\\s+(\\d{4})\\b");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            // "09 Aug, 2026" / "09 Aug 2026" -- real HDFC statements render Due Date this way
            // (see GRID_DUE_DATE_LABEL's own doc comment for why it isn't a plain "Label: Value" line).
            DateTimeFormatter.ofPattern("d MMM, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
    };

    // Some real statements (verified against an actual HDFC "Tata Neu Plus" credit card export)
    // lay the payment-summary block out as a genuine grid -- a row of labels ("... MINIMUM DUE
    // DUE DATE"), then a row of values a line or two later ("C200.00 09 Aug, 2026") -- rather
    // than "Label: Value" text PAYMENT_DUE_DATE above can match on a single line. This is a
    // best-effort fallback for that shape specifically: find a line containing the label, then
    // look at the next few lines for the first value of the expected shape, which by construction
    // (values render in the same left-to-right order as their labels) corresponds to it. Bounded
    // to a few lines so it can't wander into unrelated text further down the page.
    //
    // Bug fix: verified against a real Axis Bank "Neo Rupay" statement whose PAYMENT SUMMARY grid
    // has FIVE labels on one header line ("Total Payment Due Minimum Payment Due Statement Period
    // Payment Due Date Statement Generation Date") -- the label this constant matches is no longer
    // the LAST thing on the line (that HDFC layout only ever had one grid label per line), so the
    // original end-anchored pattern silently stopped matching real due-date grids with more than
    // one column. Widened from "ends with" to "contains." That same real value row also has TWO
    // other date-shaped values before the actual due date ("24/06/2026 - 22/07/2026", the
    // Statement Period range) -- findGridValue's own range-exclusion (see its doc comment) is what
    // keeps this from grabbing the period's start date instead of the real due date.
    private static final Pattern GRID_DUE_DATE_LABEL = Pattern.compile("(?i)\\bdue\\s+date\\b");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b|\\b\\d{1,2}\\s+[A-Za-z]{3,9}\\.?,?\\s+\\d{4}\\b");
    // A date immediately preceded or followed by " - " is one half of an explicit range (e.g. a
    // Statement Period column, "24/06/2026 - 22/07/2026") -- excluded from findGridValue's
    // date-shape scan so a standalone field like Payment Due Date is never confused with the
    // period's own start/end date sharing the same grid row.
    private static final Pattern DATE_RANGE_MEMBER = Pattern.compile("\\s-\\s\\S|\\S\\s-\\s");

    // Bug fix: same real Axis Bank statement's "Credit Limit" column -- also a grid label/value
    // row, never a same-line "Label: Value" -- see CREDIT_LIMIT above, which only ever matches
    // that same-line shape. "Available Credit Limit" and "Available Cash Limit" both share the
    // literal substring "Credit Limit"/"Limit" with the plain "Credit Limit" column this targets,
    // so the negative lookbehind excludes the "Available " variant specifically (verified this is
    // the ONLY overlapping label on this real statement's header row).
    private static final Pattern GRID_CREDIT_LIMIT_LABEL = Pattern.compile("(?i)(?<!available )\\bcredit\\s+limit\\b");
    // Bug fix: verified against a real HDFC "Tata Neu Plus" statement whose Credit Limit grid
    // value is a whole rupee amount with no decimal places at all ("78,000", not "78,000.00") --
    // requiring a literal "\.\d{2}" suffix (the original shape, sized against a DIFFERENT real
    // statement whose credit limit did have decimals) silently matched nothing on this file.
    // Widened to require EITHER a decimal suffix OR proper comma-grouped thousands formatting
    // (not just "requires digits", which would then wrongly match the digit-prefix of a masked
    // card number like "653047******7550" -- that value has no comma at all, so it still can't
    // match this pattern; a genuine amount in this locale always has at least one comma group
    // once it's large enough to need one).
    private static final Pattern AMOUNT_LIKE = Pattern.compile("\\b\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{2})?\\b|\\b[\\d,]+\\.\\d{2}\\b");
    private static final int GRID_VALUE_SEARCH_WINDOW = 3;

    // LEADING_NAME_LINE: a third real-world account-holder shape, distinct from both
    // ACCOUNT_HOLDER's "Label: Value" line and ACCOUNT_NAME_TRAILING_LABEL's "<value> Account
    // Name" line -- here there's no label AT ALL, anywhere. Verified against two independent real
    // statements from two different banks (a Bank of Baroda savings account, an Axis Bank Neo
    // Rupay credit card) -- both put the plain holder name as the literal FIRST line of the
    // document's own pre-table text, with nothing labeling it as such. Deliberately conservative,
    // since this is the weakest signal of the three account-holder patterns (no label to anchor
    // on at all): requires the line to consist of nothing but an optional courtesy title plus 2-4
    // capitalized words (no digits, no punctuation beyond an optional trailing period on the
    // title) -- capped the same way ACCOUNT_NAME_TRAILING_LABEL already caps its own word count,
    // for the same reason (a longer line is prose or a banner, not a name) -- and, critically,
    // rejected outright if BankRegistry recognizes the line as a bank's own name/alias. Without
    // that last check this would misread every existing fixture's leading "AXIS BANK"/"HDFC BANK"
    // letterhead line as if it were the account holder -- both shape-match identically to a real
    // name (two capitalized words, no digits).
    //
    // Bug fix: originally restricted to i==0 exactly, on the assumption the two real documents
    // that motivated this both had the name as their literal first extracted line. A real Kotak
    // Mahindra Bank statement breaks that assumption -- its first two lines are a generic title
    // ("Account Statement") and a date range ("01 Jul 2026 - 31 Jul 2026") before the holder's
    // name appears on the third. Widened to scan the first few lines (bounded, same spirit as
    // GRID_VALUE_SEARCH_WINDOW, so it can't wander into the transaction table's own boilerplate),
    // but that alone isn't safe on its own: "Account Statement" itself shape-matches this pattern
    // exactly as well as a real name does (two capitalized words, no digits), so LEADING_TITLE_WORDS
    // rejects any candidate containing one of a small set of generic statement-vocabulary words no
    // real person is named after -- the same overreach-prevention shape as the BankRegistry check.
    // Bug fix: same underlying defect as ACCOUNT_NAME_TRAILING_LABEL above -- the leading (?i)
    // covered the WHOLE pattern, so the "2-4 capitalized words" this doc comment describes was
    // never actually enforced ([a-z] under CASE_INSENSITIVE matches uppercase too, and vice versa).
    // Verified against the same real ICICI statement: an unrelated disclosure sentence left an
    // all-lowercase trailing fragment (two lowercase words, trailing period) as its own extracted
    // line, which shape-matched this pattern exactly and was captured as the account holder.
    // (?i:...) scopes case-insensitivity to just the courtesy-title prefix (mr/Mr/MR all valid);
    // each name word must now genuinely start with an uppercase letter, which accepts both Title
    // Case ("Ravi Kumar") and ALL CAPS ("RAVI KUMAR") -- both real, observed holder-name renderings
    // (genericized per the Synthetic Fixture Policy) -- while finally rejecting all-lowercase prose.
    private static final Pattern LEADING_NAME_LINE = Pattern.compile(
            "^(?:(?i:mr|mrs|ms|dr|m/s)\\.?\\s+)?[A-Z][A-Za-z]*(?:\\s+[A-Z][A-Za-z]*){1,3}\\.?$");
    private static final int LEADING_NAME_LINE_SEARCH_WINDOW = 5;
    // "name" included defensively: with ACCOUNT_HOLDER now recognizing a bare "Name" label
    // (see its own doc comment), a document whose "Name" label line somehow reaches this
    // fallback anyway (e.g. a future layout where the label and value split across lines) must
    // not have "Name" itself swept into LEADING_NAME_LINE's captured text.
    private static final java.util.Set<String> LEADING_TITLE_WORDS = java.util.Set.of(
            "account", "statement", "card", "credit", "savings", "current", "passbook",
            "details", "summary", "bank", "name");

    private static Pattern labelPattern(String label) {
        return Pattern.compile("(?i)^\\s*" + label + "\\s*:?\\s*(.+)$");
    }

    /**
     * @param accountNumberFullForHashingOnly the unmasked account number, present ONLY so
     *        {@link com.finora.imports.product.ProductIdentity} can hash it into a stable key.
     *        Never persisted, never returned over the API, never logged -- the hash travels, the
     *        number does not. Kept out of {@code toString()} for the same reason (see below).
     */
    public record ExtractedMetadata(
            String accountHolderName, String accountNumberMasked, String branchName,
            String ifscCode, LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
            java.math.BigDecimal creditLimit, LocalDate paymentDueDate,
            String accountNumberFullForHashingOnly
    ) {
        /** Overridden so an unmasked account number can never reach a log line, an exception
         *  message, or a debugger-friendly dump by someone printing the record. The masked form is
         *  already in here and is what anyone reading this actually wants. */
        @Override
        public String toString() {
            return "ExtractedMetadata[accountHolderName=" + accountHolderName
                    + ", accountNumberMasked=" + accountNumberMasked
                    + ", branchName=" + branchName + ", ifscCode=" + ifscCode
                    + ", statementPeriodStart=" + statementPeriodStart
                    + ", statementPeriodEnd=" + statementPeriodEnd
                    + ", creditLimit=" + creditLimit + ", paymentDueDate=" + paymentDueDate
                    + ", accountNumberFullForHashingOnly=" 
                    + (accountNumberFullForHashingOnly == null ? "null" : "<redacted>") + "]";
        }
    }

    public ExtractedMetadata extract(List<String> preTableLines) {
        return extract(preTableLines, null);
    }

    /** Same as {@link #extract(List)}, plus records GRID_METADATA_FALLBACK/
     *  GRID_METADATA_TRAILING_LABEL capability activations onto {@code ctx} as they fire (Phase 1
     *  "capture facts" -- docs/engineering/financial-document-intelligence-principles.md).
     *  {@code ctx} is nullable -- callers with no DocumentContext in scope get the old behavior. */
    public ExtractedMetadata extract(List<String> preTableLines, DocumentContext ctx) {
        String accountHolderName = null;
        String accountNumberMasked = null;
        String accountNumberFull = null;
        String branchName = null;
        String ifscCode = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        java.math.BigDecimal creditLimit = null;
        LocalDate paymentDueDate = null;

        // Bug fix: every primary "Label: Value" extraction below used to commit on EVERY matching
        // line rather than only the first, so whichever occurrence a field's label happened to
        // appear at LAST in the document silently won -- found via a real ICICI credit-card
        // statement whose genuine early Credit Limit field was overwritten by a later, entirely
        // fictional "Credit Limit" figure from the MITC section's worked example of how Minimum
        // Amount Due is calculated. A real field is stated once, prominently, near the top of a
        // statement; any later occurrence of the same label is either a harmless repeat or exactly
        // this kind of unrelated boilerplate, never something that should override an
        // already-found answer. Every GRID_*/TRAILING_LABEL fallback below already guarded its own
        // assignment this way -- these seven are now consistent with that, not exceptions to it.
        for (int i = 0; i < preTableLines.size(); i++) {
            String line = preTableLines.get(i);
            if (accountHolderName == null) {
                String holder = firstGroup(ACCOUNT_HOLDER, line);
                if (holder != null) { accountHolderName = holder; continue; }
            }

            if (accountNumberMasked == null) {
                String acctNo = firstGroup(ACCOUNT_NUMBER, line);
                if (acctNo != null) {
                    accountNumberFull = acctNo;
                    accountNumberMasked = com.finora.imports.CsvParser.maskAccountNumber(acctNo);
                    continue;
                }
            }

            if (branchName == null) {
                String branch = firstGroup(BRANCH, line);
                if (branch != null) { branchName = branch; continue; }
            }

            if (ifscCode == null) {
                String ifsc = firstGroup(IFSC, line);
                if (ifsc != null) { ifscCode = ifsc.toUpperCase(); continue; }
            }

            // Bug fix: unlike the other six guarded fields, a "Statement Period" match fills TWO
            // variables from one line, and parsePeriod can legitimately produce just one of them
            // (e.g. a line with no "to" separator). Committing a partial parse the way the old
            // unconditional assignment did would permanently strand the other half at null under
            // this guard (periodStart/periodEnd would never both be null again, so this block could
            // never re-run) -- and half-filling from one line, then completing the other half from
            // a later, possibly-unrelated line, risks stitching together a period that never
            // appeared as such in the document. Same discipline creditLimit/paymentDueDate already
            // apply below: only commit when the captured text actually parses as the expected type
            // -- here, BOTH halves -- so a partial match is treated as no match at all and the loop
            // keeps looking for a line that genuinely states the whole period together.
            if (periodStart == null && periodEnd == null) {
                String period = firstGroup(STATEMENT_PERIOD, line);
                if (period != null) {
                    LocalDate[] parsed = parsePeriod(period);
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        continue;
                    }
                }
            }

            // Checked ahead of PAYMENT_DUE_DATE below since "Credit Limit" is the more specific
            // label of the two (a payment-summary block commonly lists both fields on separate
            // lines, and neither regex is a prefix of the other, but ordering follows the same
            // "most specific signal first" convention as the rest of this loop).
            //
            // Bug fix: verified against the same real HDFC statement as AMOUNT_LIKE's own doc
            // comment -- its multi-column header line "TOTAL CREDIT LIMIT (Including Cash)
            // AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT" fully satisfies CREDIT_LIMIT's
            // same-line pattern (the label matches at the start, and the greedy trailing "(.+)$"
            // just soaks up "AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT" as if THAT were the
            // value). The old code committed to that non-numeric "value" as null and unconditionally
            // continued -- permanently skipping this line before the GRID_CREDIT_LIMIT_LABEL
            // fallback below ever got a chance to run on it. Only commit (and skip the grid
            // fallback) when the captured text actually parses as the expected type -- same
            // discipline CsvParser.firstParseableAmount already established for the identical
            // "label matched, but what follows isn't really the value" shape.
            if (creditLimit == null) {
                String limit = firstGroup(CREDIT_LIMIT, line);
                if (limit != null) {
                    java.math.BigDecimal parsedLimit = com.finora.imports.CsvParser.parseNumeric(limit);
                    if (parsedLimit != null) { creditLimit = parsedLimit; continue; }
                }
            }

            if (paymentDueDate == null) {
                String dueDate = firstGroup(PAYMENT_DUE_DATE, line);
                if (dueDate != null) {
                    LocalDate parsedDueDate = parseDate(dueDate);
                    if (parsedDueDate != null) { paymentDueDate = parsedDueDate; continue; }
                }
            }

            if (paymentDueDate == null) {
                Matcher dueDateLabel = GRID_DUE_DATE_LABEL.matcher(line);
                if (dueDateLabel.find()) {
                    // Same line first: a real credit-card statement's due-date UI element (a
                    // colored badge/pill) had its own "Pay Now" button text merged onto the same
                    // extracted line AHEAD of the label -- "Pay Now Payment due date 08 May 2026
                    // ...". The same-line anchored PAYMENT_DUE_DATE pattern requires the label at
                    // the very start of the line, so it never matched here at all; it isn't a date-
                    // format problem, the label simply isn't first. Searching from right after
                    // wherever "due date" was found -- not the start of the line -- finds the real
                    // value regardless of what precedes the label, the same "label, then the first
                    // date-shaped thing after it" contract findGridValue already uses across lines.
                    String sameLineValue = firstMatchAfter(line, dueDateLabel.end(), DATE_LIKE, DATE_RANGE_MEMBER);
                    if (sameLineValue != null) {
                        paymentDueDate = parseDate(sameLineValue);
                        if (ctx != null && paymentDueDate != null) ctx.record("GRID_METADATA_FALLBACK");
                        if (paymentDueDate != null) continue;
                    }

                    // Genuine multi-line grid: label and value are on separate lines entirely (see
                    // GRID_DUE_DATE_LABEL's own doc comment for the real Axis/HDFC layouts this
                    // covers) -- tried after the same-line search, not instead of it, so neither
                    // shape regresses the other.
                    String value = findGridValue(preTableLines, i, DATE_LIKE, DATE_RANGE_MEMBER);
                    if (value != null) paymentDueDate = parseDate(value);
                    if (ctx != null && paymentDueDate != null) ctx.record("GRID_METADATA_FALLBACK");
                    continue;
                }
            }

            if (creditLimit == null && GRID_CREDIT_LIMIT_LABEL.matcher(line).find()) {
                String value = findGridValue(preTableLines, i, AMOUNT_LIKE, null);
                if (value != null) creditLimit = com.finora.imports.CsvParser.parseNumeric(value);
                if (ctx != null && creditLimit != null) ctx.record("GRID_METADATA_FALLBACK");
                continue;
            }

            // GRID_METADATA_TRAILING_LABEL fallbacks (see that constant's own doc comment) -- only
            // consulted once the "label first" checks above have already had their chance on every
            // line, so a document using the ordinary "Label: Value" shape is completely unaffected.
            if (accountNumberMasked == null) {
                Matcher acctNoMatch = ACCOUNT_NUMBER_TRAILING_LABEL.matcher(line);
                if (acctNoMatch.matches()) {
                    accountNumberFull = acctNoMatch.group(1);
                    accountNumberMasked = com.finora.imports.CsvParser.maskAccountNumber(acctNoMatch.group(1));
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            if (accountHolderName == null) {
                Matcher holderMatch = ACCOUNT_NAME_TRAILING_LABEL.matcher(line);
                if (holderMatch.find()) {
                    accountHolderName = holderMatch.group(1).trim();
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            // CARD_NUMBER_TRAILING_LABEL (see that constant's own doc comment -- the real HDFC
            // shape this exists for). Tried before the same-line-anywhere fallback below since a
            // value-before-label match is more specific than a bare "label found somewhere" search.
            if (accountNumberMasked == null) {
                Matcher cardNoTrailing = CARD_NUMBER_TRAILING_LABEL.matcher(line);
                if (cardNoTrailing.find() && looksLikeCardOrAccountNumber(cardNoTrailing.group(1))) {
                    String[] normalized = normalizeCardOrAccountNumberValue(cardNoTrailing.group(1));
                    accountNumberMasked = normalized[0];
                    accountNumberFull = normalized[1];
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            // CARD_NUMBER_LABEL, same-line-anywhere or a genuine multi-line grid (see that
            // constant's own doc comment -- the real Kotak shape for the former: text like
            // "(Principal Outstanding)" precedes the label on the same line, defeating a
            // start-anchored match). Tried in that order, same-line first since it's the more
            // specific match, once the label has actually been found on this line at all.
            //
            // Known bound on the same-line search: firstMatchAfter returns its first candidate
            // outright when passed no exclude pattern, so if a too-short non-identifying token sat
            // between the label and the real value on the same line, looksLikeCardOrAccountNumber
            // would reject it and this would fall through to the grid search below rather than
            // retrying further right on the same line. Not worth a retry loop for a shape no real
            // document in this session's corpus (25 real statements, credit-card and savings)
            // exercises -- see the Phase 1C/1C.1 real-corpus verification.
            if (accountNumberMasked == null) {
                Matcher cardLabel = CARD_NUMBER_LABEL.matcher(line);
                if (cardLabel.find()) {
                    String sameLineValue = firstMatchAfter(line, cardLabel.end(), CARD_NUMBER_VALUE, null);
                    if (sameLineValue != null && looksLikeCardOrAccountNumber(sameLineValue)) {
                        String[] normalized = normalizeCardOrAccountNumberValue(sameLineValue);
                        accountNumberMasked = normalized[0];
                        accountNumberFull = normalized[1];
                        if (ctx != null) ctx.record("GRID_METADATA_FALLBACK");
                        continue;
                    }
                    // Genuine multi-line grid: the label's own line carries no value of its own --
                    // verified against a real SBI credit-card statement, whose "Credit Card
                    // Number"-labelled line has nothing else on it at all; the masked number is on
                    // the very next line. Same findGridValue contract payment-due-date/credit-limit
                    // already use, applied to this field for the first time.
                    //
                    // Bug fix: this used to try findGridValue whenever the same-line search simply
                    // failed, with no check on WHY it failed -- CARD_NUMBER_LABEL's own unanchored
                    // match (needed for Kotak's leading-text case) also fires on an incidental
                    // mention of "account number"/"card number" buried mid-sentence in unrelated
                    // prose, and a 3-line forward scan from THAT position can land on some unrelated
                    // nearby digit-shaped token. Confirmed against a real corpus sweep: three
                    // documents (a credit-card statement and two savings statements) produced a
                    // spurious grid match this way, every one of them a label mid-sentence with
                    // several more words of prose following it on the same line -- never digits.
                    // SBI's own genuine line has ZERO characters after the label match; every false
                    // positive found had well over a dozen. Requiring the label to be the last real
                    // content on its line -- looser grid inference needs a stronger shape
                    // requirement than same-line matching does, not the same one -- is what actually
                    // distinguishes "this line IS the label" from "this line MENTIONS the label."
                    // A trailing colon is tolerated -- the same optional-colon punctuation every
                    // other label in this class already allows (see labelPattern) -- since a
                    // "Label:" line with its value on the next line is an ordinary formatting
                    // choice, not evidence the label was merely mentioned in passing.
                    boolean labelIsLastContentOnLine =
                            line.substring(cardLabel.end()).trim().replaceFirst("^:", "").trim().isEmpty();
                    if (labelIsLastContentOnLine) {
                        String gridValue = findGridValue(preTableLines, i, CARD_NUMBER_VALUE, null);
                        if (gridValue != null && looksLikeCardOrAccountNumber(gridValue)) {
                            String[] normalized = normalizeCardOrAccountNumberValue(gridValue);
                            accountNumberMasked = normalized[0];
                            accountNumberFull = normalized[1];
                            if (ctx != null) ctx.record("GRID_METADATA_FALLBACK");
                        }
                    }
                    continue;
                }
            }
            if (accountNumberMasked == null) {
                Matcher cardEndingMatch = CARD_ENDING_DIGITS.matcher(line);
                if (cardEndingMatch.find()) {
                    accountNumberMasked = "••••" + cardEndingMatch.group(1);
                    if (ctx != null) ctx.record("CARD_ENDING_DIGITS_IDENTITY");
                    continue;
                }
            }
            // LEADING_NAME_LINE (see that constant's own doc comment) -- bounded to the first few
            // lines only, and only once every labeled shape above has already had its chance to
            // find a holder name a more reliable way. A candidate is rejected (silently, scanning
            // continues to the next line within the window) if it names a known bank, or if it
            // contains a generic statement-vocabulary word no real person is named after.
            if (accountHolderName == null && i < LEADING_NAME_LINE_SEARCH_WINDOW
                    && LEADING_NAME_LINE.matcher(line.trim()).matches()
                    && containsNoLeadingTitleWord(line)
                    && BankRegistry.UNKNOWN_ID.equals(BankRegistry.detect("", List.of(line)).id())) {
                accountHolderName = line.trim();
                // Wired late. The registry declared LEADING_NAME_LINE and this branch has always
                // implemented it, but nothing recorded it -- so it reported as never-activated
                // forever, which is indistinguishable from "no document has needed it". That is the
                // one signal the coverage map exists to produce.
                if (ctx != null) ctx.record("LEADING_NAME_LINE");
                if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                continue;
            }
            if (ifscCode == null) {
                Matcher ifscMatch = IFSC_SHAPE.matcher(line);
                if (ifscMatch.find()) {
                    ifscCode = ifscMatch.group().toUpperCase();
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            // Bug fix: same partial-parse hazard as the primary STATEMENT_PERIOD block above --
            // only commit when parsePeriod resolved both halves, so a malformed grid-label match
            // can't strand this AND-guarded pair at a permanent half-null state.
            if (periodStart == null && periodEnd == null) {
                Matcher periodMatch = STATEMENT_PERIOD_TRAILING_LABEL.matcher(line);
                if (periodMatch.matches()) {
                    LocalDate[] parsed = parsePeriod(periodMatch.group(1).trim());
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                        continue;
                    }
                }
            }
        }

        return new ExtractedMetadata(accountHolderName, accountNumberMasked, branchName, ifscCode,
                periodStart, periodEnd, creditLimit, paymentDueDate, accountNumberFull);
    }

    /** Shared by every grid-metadata fallback (see {@link #GRID_DUE_DATE_LABEL}/
     *  {@link #GRID_CREDIT_LIMIT_LABEL}'s own doc comments): scans the few lines after a label
     *  line for the first substring matching {@code valuePattern}, skipping any match that also
     *  matches {@code exclude} (e.g. a date that's one half of an explicit range -- null to skip
     *  no matches), and returns the raw matched text -- null (not a thrown exception) if nothing
     *  usable turns up within the window, same "genuinely null when the file didn't carry enough
     *  signal" discipline every other field here follows. Bounded to a few lines so it can't
     *  wander into unrelated text further down the page. */
    private String findGridValue(List<String> lines, int labelLineIndex, Pattern valuePattern, Pattern exclude) {
        int end = Math.min(lines.size(), labelLineIndex + 1 + GRID_VALUE_SEARCH_WINDOW);
        for (int j = labelLineIndex + 1; j < end; j++) {
            String candidateLine = lines.get(j);
            Matcher m = valuePattern.matcher(candidateLine);
            while (m.find()) {
                if (exclude == null || !exclude.matcher(candidateLine.substring(
                        Math.max(0, m.start() - 3), Math.min(candidateLine.length(), m.end() + 3))).find()) {
                    return m.group();
                }
            }
        }
        return null;
    }

    /** Same value-shape/exclusion contract as {@link #findGridValue}, but searches within ONE
     *  already-known line starting at a given character offset, rather than across several
     *  following lines -- for a label whose value shares its own line but not the label's own
     *  start (see the payment-due-date same-line fallback above for why "the label is somewhere
     *  in this line" and "the label starts this line" are genuinely different real shapes). */
    private String firstMatchAfter(String line, int fromIndex, Pattern valuePattern, Pattern exclude) {
        Matcher m = valuePattern.matcher(line);
        while (m.find(fromIndex)) {
            fromIndex = m.end();
            if (exclude == null || !exclude.matcher(line.substring(
                    Math.max(0, m.start() - 3), Math.min(line.length(), m.end() + 3))).find()) {
                return m.group();
            }
        }
        return null;
    }

    /** Whether a CARD_NUMBER_VALUE-shaped candidate is actually identifying, not noise picked up
     *  because it happened to sit near a recognized label. Checked after the regex match, not
     *  folded into it, so the length/digit-count/trailing-digit requirements stay easy to read and
     *  to test independently. Bounds mirror ACCOUNT_NUMBER_TRAILING_LABEL's own existing 6-20
     *  digit range for consistency. Requires the LAST character to be a real digit -- every masked
     *  shape observed on a real statement keeps its final group visible; a value ending in a mask
     *  character would mean nothing about the identity was ever actually shown, i.e. not a
     *  genuine masked number at all.
     *
     *  <p>Phase 1C.1: lowered from >= 4 to >= 2 visible digits -- a real SBI credit-card statement
     *  masks all but its last 2 digits, which the original 4-digit floor rejected outright even
     *  though findGridValue correctly located it. Verified safe across the full 25-document real
     *  corpus (7 credit-card, 18 savings): the same-line/trailing-label paths produce zero new
     *  matches at the lower threshold anywhere in the corpus (every candidate they'd ever accept is
     *  tightly anchored immediately next to its label), and this method deliberately applies the
     *  identical bound regardless of which caller found the candidate -- label proximity and
     *  findGridValue's own narrow search window are what keep this safe, not an assumption about
     *  how many digits a bank chooses to mask. A mask-character requirement was considered and
     *  rejected: a real HSBC statement's own account-number field is fully unmasked, so requiring
     *  one would have rejected a genuine match, not just noise. */
    private static boolean looksLikeCardOrAccountNumber(String candidate) {
        String stripped = candidate.replaceAll("[\\s-]", "");
        if (stripped.length() < 6 || stripped.length() > 20) return false;
        long digitCount = stripped.chars().filter(Character::isDigit).count();
        return digitCount >= 2 && Character.isDigit(stripped.charAt(stripped.length() - 1));
    }

    /**
     * Decides how to store a captured card/account-number value. An already-masked value
     * (contains an X/x/* mask character) is stored EXACTLY as printed, trimmed only -- this
     * codebase has no way to recover the digits a bank chose to hide and must never fabricate a
     * different mask shape than the one actually printed. A genuine unmasked digit run is masked
     * the same way ACCOUNT_NUMBER's own value already is, so the full number can still be hashed
     * for product identity exactly as that existing path does.
     *
     * @return a two-element array: [0] the value for accountNumberMasked, [1] the unmasked full
     *         number for accountNumberFullForHashingOnly, or null when the value was already masked
     */
    private static String[] normalizeCardOrAccountNumberValue(String captured) {
        String trimmed = captured.trim();
        boolean alreadyMasked = trimmed.chars().anyMatch(c -> c == 'X' || c == 'x' || c == '*');
        if (alreadyMasked) return new String[]{trimmed, null};
        return new String[]{com.finora.imports.CsvParser.maskAccountNumber(trimmed), trimmed};
    }

    private String firstGroup(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (m.matches()) {
            String value = m.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** See {@link #LEADING_TITLE_WORDS}'s own doc comment -- true unless one of the line's own
     *  words (case-insensitive, punctuation-stripped) is a generic statement-vocabulary word. */
    private boolean containsNoLeadingTitleWord(String line) {
        for (String word : line.trim().split("\\s+")) {
            String normalized = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (LEADING_TITLE_WORDS.contains(normalized)) return false;
        }
        return true;
    }

    /** "01-07-2026 to 31-07-2026" -> [start, end]. Either element is null if that half didn't
     *  parse -- a genuinely malformed period string shouldn't throw and abort the whole import,
     *  just leave the field(s) it couldn't make sense of unset, same as everywhere else here. */
    private LocalDate[] parsePeriod(String text) {
        String[] parts = text.split("(?i)\\s+to\\s+");
        LocalDate start = parts.length > 0 ? parseDate(parts[0].trim()) : null;
        LocalDate end = parts.length > 1 ? parseDate(parts[1].trim()) : null;
        return new LocalDate[]{start, end};
    }

    private LocalDate parseDate(String raw) {
        LocalDate parsed = tryEveryFormat(raw);
        if (parsed != null) return parsed;

        // Retry only, never a first attempt -- same discipline, and the same shared helper, as
        // com.finora.imports.CsvParser.parseDate's own ordinal-suffix retry. Defensive coverage
        // for a general date-parsing gap (no formatter above expresses an ordinal suffix --
        // DateTimeFormatter has no token for one), not tied to a specific reproduced document --
        // see CsvParser.ORDINAL_DAY_SUFFIX's own comment for why.
        String deOrdinalized = com.finora.imports.CsvParser.stripOrdinalDaySuffix(raw);
        return deOrdinalized.equals(raw) ? null : tryEveryFormat(deOrdinalized);
    }

    private LocalDate tryEveryFormat(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(raw, fmt); } catch (Exception ignored) {}
        }
        return null;
    }
}
