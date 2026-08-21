package com.finora.imports;

import com.opencsv.CSVReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mechanical CSV parsing: reads the raw file, locates the real header row, and zips each data
 * row into a header-keyed map. Deliberately knows nothing about which column *means* what
 * (date vs amount vs category) or what a valid transaction looks like — that interpretation
 * belongs to {@link TransactionNormalizer} (row -> transaction fields) and
 * {@link StatementValidator} (row -> account-level fields). Keeping this class ignorant of
 * business meaning is what makes it reusable for both.
 *
 * Extracted from the original monolithic CsvImportService as part of the v56 modularization
 * pass (see docs/engineering/CODING_STANDARDS.md and the Finora v56 roadmap, Phase 2).
 */
@Component
public class CsvParser {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            // Dot-separated, verified against a real ICICI Bank savings-account transaction
            // history ("28.07.2026"). Same day-month-year order as the slash and dash forms above
            // and no more ambiguous than either -- only the separator differs -- but it was not
            // listed, so every row on that export failed to anchor.
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            // Bug fix: verified against a real Kotak Mahindra Bank statement -- "01 Jul 2026," a
            // day-month(abbreviated name)-year format none of the above patterns match. Locale.ENGLISH
            // pinned explicitly so parsing this format doesn't depend on the JVM's default locale
            // (which may not even use Latin month abbreviations on a different machine).
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),

            // TWO-DIGIT YEARS. Every pattern above requires four, so "01/07/26" -- the single most
            // common date rendering in Indian bank statements -- did not parse at all. Measured on
            // a real 39-page statement: 157 rows whose date cell was shaped "99/99/99 XXX-XXXXXX",
            // none of which could anchor, because parseDate rejected the year before anything else
            // was even considered.
            //
            // Listed AFTER the four-digit forms deliberately. LocalDate.parse must consume the
            // whole string, so "01/07/2026" cannot match "dd/MM/yy" -- but ordering makes that
            // independent of the resolver's strictness rather than reliant on it.
            //
            // Java resolves "yy" against a base of 2000, so 26 is 2026 and never 1926. That is the
            // right answer here and not a coincidence worth leaving implicit: a bank statement is
            // a recent document, and the alternative reading would place transactions a century
            // before the product existed.
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            caseInsensitive("dd MMM yy"),
            caseInsensitive("dd-MMM-yy"),
            caseInsensitive("dd-MMM-yyyy"),
            // The four-digit space-separated form, case-insensitively. "dd MMM yyyy" above is
            // case-SENSITIVE, so it reads "01 Jul 2026" and rejects "01 JUL 2026" -- and statements
            // print month abbreviations in caps at least as often as in title case.
            caseInsensitive("dd MMM yyyy"),

            // MONTH-NAME-FIRST. Verified against a real Bandhan Bank savings statement, whose
            // Transaction Date and Value Date columns both read "July29, 2026" -- month name
            // first, the day welded straight onto it with no separator, then a comma before the
            // year. Every pattern above puts the DAY first, so none of them can reach that shape
            // and parseDate returned null for all six date cells in the table.
            //
            // The consequence was total, not partial. PdfTableLocator.hasDateValue asks this same
            // parser whether a row is a new transaction anchor, so with no date parseable, no row
            // was ever an anchor: all three transactions fell into the leading-narration buffer,
            // collapsed into one map, and a perfectly clean six-column table (correct columns,
            // correct amounts, correct Dr/Cr markers, a balance chain that reconciles to the
            // statement's own summary) staged ZERO transactions -- HTTP 422, "found a transaction
            // table in this statement but could not read any transactions from it."
            //
            // parseLenient() is what lets ONE pattern cover both spellings of the month: lenient
            // text parsing accepts an abbreviated name where the pattern asks for the full one, so
            // "July 29, 2026" and "Jul 29, 2026" both read without a second formatter. The missing
            // separator is deliberately NOT encoded here -- a pattern can only answer "is there a
            // space" one way, and real statements print it both ways, so that half is handled by
            // parseDate's retry below.
            monthNameFirst("MMMM d, yyyy")
    );

    /**
     * A month-name pattern that accepts any capitalisation.
     *
     * <p>{@code DateTimeFormatter.ofPattern} matches month names case-sensitively, so a formatter
     * built for {@code MMM} accepts "Jul" and rejects "JUL". Bank statements use both, frequently
     * in caps, and the rejection is silent -- the row simply never anchors.
     */
    private static DateTimeFormatter caseInsensitive(String pattern) {
        return new java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    /**
     * {@link #caseInsensitive}, plus lenient TEXT matching so a {@code MMMM} pattern also accepts
     * the abbreviated month name.
     *
     * <p>Java treats {@code MMMM} (full) and {@code MMM} (abbreviated) as separate name stores
     * under strict parsing: a formatter built for {@code MMMM} reads "July" and rejects "Jul", and
     * one built for {@code MMM} does the exact opposite. Which one a statement prints is a
     * rendering choice its bank made, not a different date format -- so covering both with two
     * near-identical formatters would be encoding that choice twice. {@code parseLenient()} makes
     * the one pattern read either.
     *
     * <p>Kept separate from {@link #caseInsensitive} rather than folded into it: lenient parsing
     * also relaxes NUMERIC fields (widths and signs), which the day-first patterns above have no
     * need of and which would quietly widen what they accept.
     */
    private static DateTimeFormatter monthNameFirst(String pattern) {
        return new java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .parseLenient()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    // Column-name hints used to locate the real header row, wherever it falls in the file. Real
    // bank/card exports (see PNB ONE's CSV, which motivated this) routinely prepend a dozen-plus
    // lines of branch/customer metadata before the actual transaction table — so the header
    // can't be assumed to be row 1. A row counts as "the header" once it has at least one cell
    // matching a date hint AND one matching an amount hint.
    private static final List<String> DATE_HEADER_HINTS = List.of(
            "date", "txn date", "transaction date", "value date");
    private static final List<String> AMOUNT_HEADER_HINTS = List.of(
            "amount", "debit", "credit", "dr amount", "cr amount", "debit amount", "credit amount",
            "withdrawal amt", "withdrawal amount", "deposit amt", "deposit amount", "withdrawal", "deposit");

    /** Plain CSVReader (not the header-aware variant) so callers can (a) locate the header row
     *  themselves instead of assuming row 1, and (b) tolerate ragged rows — real exports like
     *  PNB ONE's end every transaction line with a trailing comma, i.e. one more field than
     *  there are header columns, which the strict header-aware reader rejects outright. */
    public List<String[]> readAll(InputStream contentStream) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(contentStream, StandardCharsets.UTF_8))) {
            return reader.readAll();
        } catch (com.opencsv.exceptions.CsvException e) {
            throw new IOException("Malformed CSV content", e);
        }
    }

    /** First row containing both a recognizable date column and a recognizable amount column,
     *  or -1 if nothing in the file looks like a transaction table header. */
    public int findHeaderRowIndex(List<String[]> rows) {
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length < 2) continue;
            boolean hasDate = false, hasAmount = false;
            for (String cell : r) {
                if (cell == null) continue;
                String norm = normalizeHeaderCell(cell);
                if (!hasDate && DATE_HEADER_HINTS.contains(norm)) hasDate = true;
                if (!hasAmount && AMOUNT_HEADER_HINTS.contains(norm)) hasAmount = true;
            }
            if (hasDate && hasAmount) return i;
        }
        return -1;
    }

    /** Zips a header row and a data row into a header-keyed map, ignoring any cells beyond the
     *  header's width (the trailing-comma case) and treating missing trailing cells as absent
     *  rather than throwing (the footer-notes case, where a row may have far fewer cells).
     *
     *  Bug 33 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). A repeated header name -- most
     *  commonly a Debit/Credit-style export that labels BOTH columns "Amount" -- used to
     *  unconditionally overwrite on collision, so only whichever occurrence was positionally last
     *  in the header row ever reached the caller. On exactly the real-world shape this actually
     *  matters for, that silently discarded real data: a debit/credit pair sharing one header name
     *  has, per row, exactly one side populated and the other blank (the same "non-blank value in
     *  the column, not just the column's presence" convention TransactionNormalizer's own credit
     *  detection already relies on) -- so a blank arriving after the real value wiped it out on
     *  every row where the pairing happened to land that way. Now keeps whichever occurrence is
     *  non-blank, regardless of which one is positionally last; if both are genuinely non-blank
     *  (truly ambiguous data, not this class's real-world case), the later one still wins, same as
     *  before. Blank/spacer headers (both reducing to the same "" key) are unaffected -- nothing
     *  looks a column up by that key, so their collision was never a data-loss risk. */
    public Map<String, String> zipRow(String[] headerRow, String[] cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int c = 0; c < headerRow.length; c++) {
            String key = headerRow[c] == null ? "" : headerRow[c].trim();
            String value = c < cells.length ? cells[c] : null;
            String existing = row.get(key);
            if (row.containsKey(key) && (value == null || value.isBlank())
                    && existing != null && !existing.isBlank()) {
                continue;
            }
            row.put(key, value);
        }
        return row;
    }

    public boolean isBlankRow(String[] cells) {
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    /**
     * Normalizes a raw header cell for hint-matching: lowercase, trimmed, and with any trailing
     * parenthetical annotation stripped — e.g. "Debit (INR)" / "Running Balance (₹)" both reduce
     * to "debit" / "running balance". Real bank exports routinely tack a currency unit onto
     * amount/balance headers, and an exact-string match against the header hints above would
     * silently reject those headers, staging zero rows even though every transaction line was
     * well-formed.
     *
     * Bug fix: verified against a real Union Bank of India statement -- a PDF header cell like
     * "Amount(₹)" doesn't always survive extraction as one contiguous string. When the ₹ glyph
     * itself extracts as nothing (a font-encoding gap, same class of quirk as the Rupee-as-"C"
     * bug found in CsvParser.parseNumeric elsewhere), PDFBox can hand back "Amount(" and ")" as
     * two SEPARATE text runs/header tokens -- "Amount(" alone has no matching close paren, so the
     * strip above never fires, "amount(" never equals the "amount" hint, and every row on the
     * statement silently failed to normalize (no amount column ever recognized) even though the
     * values themselves were bucketed correctly. Stripped as a second pass, after the
     * closed-parenthetical case above, so a genuinely empty trailing "(" left dangling at the end
     * of a header cell is also treated as noise.
     */
    public static String normalizeHeaderCell(String cell) {
        if (cell == null) return "";
        String s = cell.trim().toLowerCase();
        // Bug fix: verified against a real Kotak credit-card statement -- its font renders the
        // Rupee glyph as a bare "r" immediately after a closing paren ("Amount (Rs.)r" for what
        // prints on screen as "Amount (Rs.)₹"), the same class of quirk as the Rupee-as-"C" bug
        // in parseNumeric (see that method's own comment), just a different fallback character
        // for a different document's font. Left unstripped, the trailing "r" sits past the
        // parenthetical strip below's end anchor, so it never fires, "amount (rs.)r" never equals
        // the "amount" hint, and every row on the statement silently failed to normalize. Scoped
        // to immediately-after-a-closing-paren so a real column name that happens to end in "r"
        // elsewhere is never touched.
        s = s.replaceAll("(?<=\\))r\\s*$", "");
        s = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        s = s.replaceAll("\\s*\\(\\s*$", "").trim();
        // Bug fix: trailing punctuation was never stripped, so an abbreviated column name could
        // not match the abbreviated hint written for it. AMOUNT_HEADER_HINTS lists "withdrawal
        // amt" and "deposit amt" -- someone knew real statements abbreviate -- but the real cell
        // is "Withdrawal Amt." WITH a period, which normalized to "withdrawal amt." and never
        // equalled the hint. On an HDFC-style export whose only amount columns are those two,
        // findHeaderRowIndex found no amount column at all and returned -1, and the user staged
        // zero transactions from a perfectly valid file.
        //
        // Only the trailing edge, and only after the parenthetical passes above, so
        // "Withdrawal Amt.(INR)" is handled too. Interior punctuation is left alone -- "Chq./Ref.No."
        // is one token to anything reading it, and rewriting its middle would change meaning rather
        // than remove noise. PdfTableLocator.matchesAnyHint already strips edge punctuation per
        // word and its comment names this exact string; this is the CSV side of the same rule.
        s = s.replaceAll("[.,;:]+$", "").trim();
        return s;
    }

    /** Case-insensitive "does this row have a column named (one of) these" check, used for
     *  presence-only signals (e.g. a Card Number column implies a credit card statement). */
    public static boolean hasHeaderMatch(Map<String, String> row, String... keys) {
        for (String k : keys) {
            for (String actual : row.keySet()) {
                if (actual != null && normalizeHeaderCell(actual).equalsIgnoreCase(k)) return true;
            }
        }
        return false;
    }

    // Case-insensitive on purpose: header text arrives verbatim from whatever bank/card portal
    // exported the file ("Txn Date" vs "date" vs "DATE"), and we'd rather recognize a known
    // column under any casing than force every bank's exact spelling into this list.
    public static String firstNonBlank(Map<String, String> row, String... keys) {
        for (String k : keys) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() != null && normalizeHeaderCell(e.getKey()).equalsIgnoreCase(k)) {
                    String v = e.getValue();
                    if (v != null && !v.isBlank()) return v;
                }
            }
        }
        return null;
    }

    // Strips a trailing time-of-day component before trying the date-only formats above -- some
    // real exports (HDFC's "DATE & TIME" column, e.g. "30/06/2026| 14:18") combine a date and a
    // 24-hour or am/pm time in one cell, sometimes separated by a literal "|" glyph rather than
    // plain whitespace (an artifact of how the statement's own PDF table renders that column's
    // visual divider). A cell with no trailing time component is left untouched (no-op match).
    private static final java.util.regex.Pattern TRAILING_TIME = java.util.regex.Pattern.compile(
            "(?i)[\\s|]+\\d{1,2}:\\d{2}(:\\d{2})?\\s*(am|pm)?$");

    // A trailing Dr/Cr marker on an amount cell shows up in at least two real forms: bare
    // ("37.94 Dr", "10,081.99 Cr" -- Axis) and parenthesized ("50000.00(Cr)", "1627.00(Dr)" --
    // Union Bank of India). One pattern per marker, optional wrapping "(...)" so both forms are
    // recognized without duplicating the detect/strip logic between detectSignFromRawAmount and
    // parseNumeric below.
    private static final java.util.regex.Pattern TRAILING_DR = java.util.regex.Pattern.compile("(?i)\\(?\\s*dr\\.?\\s*\\)?\\s*$");
    private static final java.util.regex.Pattern TRAILING_CR = java.util.regex.Pattern.compile("(?i)\\(?\\s*cr\\.?\\s*\\)?\\s*$");

    // The boundary between a letter and a digit with nothing between them -- "July29, 2026" on a
    // real Bandhan Bank statement, where the month name and the day are rendered as one
    // uninterrupted token (confirmed at the glyph level: PDFBox hands that cell back as a single
    // text run, so this is what the document itself prints, not an extraction artifact that could
    // be fixed upstream). Zero-width on both sides, so replacing it INSERTS a separator rather
    // than consuming a character.
    private static final java.util.regex.Pattern MISSING_SEPARATOR =
            java.util.regex.Pattern.compile("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})");

    // Ordinal day suffixes -- "04th", "21st", "2nd", "3rd". None of DATE_FORMATS' patterns can
    // express "digits, then one of st/nd/rd/th, case-insensitive" -- DateTimeFormatter has no
    // token for an ordinal suffix -- so a date printed this way fails every format outright. This
    // strips it textually before a retry, the same shape of fix as MISSING_SEPARATOR below.
    // Defensive coverage for a real, general date-parsing gap, not a fix reproduced against a
    // specific real document -- the real document this parser was originally investigated against
    // (a credit-card statement's Payment Due Date field) turned out on closer inspection to have
    // no ordinal suffix at all; see PdfMetadataExtractor's same-line label-anchor fallback for that
    // document's actual root cause. Captures the digit run so a
    // single-digit ordinal ("2nd", "3rd", "1st") can be zero-padded back to the two digits every
    // "dd"-based formatter in DATE_FORMATS requires -- LocalDate.parse is strict about width, so
    // "2nd Aug 2026" stripped to "2 Aug 2026" would still fail every format in the list. Anchored
    // to a digit run immediately followed by the suffix and a word boundary, so it only strips a
    // genuine ordinal marker (day "21st") and not a coincidental "st"/"nd"/"rd"/"th" that happens
    // to follow a number for an unrelated reason elsewhere in the string.
    private static final java.util.regex.Pattern ORDINAL_DAY_SUFFIX =
            java.util.regex.Pattern.compile("(?i)(\\d{1,2})(?:st|nd|rd|th)\\b");

    public static LocalDate parseDate(String raw) {
        String withoutTime = TRAILING_TIME.matcher(raw).replaceFirst("");
        LocalDate parsed = tryEveryFormat(withoutTime);
        if (parsed != null) return parsed;

        // Only ever a RETRY, never a first attempt. Every format that parses today is reached with
        // the string exactly as the document wrote it, so this cannot change any existing
        // document's answer -- it can only rescue a cell that had already failed outright. That
        // ordering is the whole safety argument for normalising the input at all: a date parser
        // that rewrites its input before looking at it is one that can silently start reading a
        // value as something other than what it says.
        String separated = MISSING_SEPARATOR.matcher(withoutTime).replaceAll(" ");
        if (!separated.equals(withoutTime)) {
            parsed = tryEveryFormat(separated);
            if (parsed != null) return parsed;
        }

        // Second retry, same discipline, tried only after the as-printed attempt has already
        // failed -- strips an ordinal day suffix (zero-padding a single-digit day left behind) and
        // nothing else. Defensive coverage, not tied to a specific reproduced document -- see
        // ORDINAL_DAY_SUFFIX's own comment above.
        String deOrdinalized = stripOrdinalDaySuffix(withoutTime);
        return deOrdinalized.equals(withoutTime) ? null : tryEveryFormat(deOrdinalized);
    }

    /**
     * Strips a trailing ordinal day suffix ("04th" -> "04", "2nd" -> "02"), zero-padding a
     * single-digit day left behind since {@link LocalDate#parse} is strict about width. Exposed
     * (not private) so {@link com.finora.imports.pdf.PdfMetadataExtractor}'s own, separately
     * maintained date parser can apply the identical retry to its own format list, rather than
     * this fix existing only for whichever caller happened to go through this class -- see that
     * class's own {@code parseDate} for why its formats aren't simply delegated here instead.
     */
    public static String stripOrdinalDaySuffix(String value) {
        java.util.regex.Matcher matcher = ORDINAL_DAY_SUFFIX.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String twoDigitDay = matcher.group(1).length() == 1 ? "0" + matcher.group(1) : matcher.group(1);
            matcher.appendReplacement(result, twoDigitDay);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static LocalDate tryEveryFormat(String value) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(value, fmt); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Infers income (true) vs. expense (false) purely from a single amount cell's own text, for
     * layouts with neither a separate Type column nor a separate Credit column -- e.g. Axis's
     * "37.94 Dr" / "10,081.99 Cr" (one Amount column, Dr/Cr embedded in the value) or HDFC's
     * leading "+" for a credit ("+ ₹440.00") with no marker at all on debit rows. Returns null
     * when the raw string carries no such signal, so callers can fall through to their own
     * default rather than this method inventing one.
     */
    public static Boolean detectSignFromRawAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (TRAILING_CR.matcher(s).find()) return true;
        if (TRAILING_DR.matcher(s).find()) return false;
        if (s.startsWith("+")) return true;
        return null;
    }

    /** DR_CR_SUFFIX capability signal (see the Capability Registry in
     *  docs/engineering/financial-document-intelligence-principles.md) -- true when {@code raw}
     *  carries a trailing Dr/Cr marker, bare or parenthesized, in either case. Shares
     *  TRAILING_DR/TRAILING_CR with {@link #detectSignFromRawAmount}/{@link #parseNumeric} so the
     *  three can never drift out of sync on what counts as "has this marker." */
    public static boolean hasTrailingDrCrMarker(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return !s.isEmpty() && (TRAILING_DR.matcher(s).find() || TRAILING_CR.matcher(s).find());
    }

    /** DATE_TIME_COLUMN capability signal -- true when {@code raw} carries a trailing time-of-day
     *  component {@link #parseDate} strips before parsing (see TRAILING_TIME's own doc comment). */
    public static boolean hasDateTimeComponent(String raw) {
        return raw != null && TRAILING_TIME.matcher(raw).find();
    }

    /**
     * Cleans a raw numeric cell into a BigDecimal, or null if it can't be parsed. Handles the
     * formatting quirks real bank exports throw at us: thousands separators, ₹/Rs/INR prefixes,
     * and the Indian-statement "Dr."/"Cr." suffix on balance columns (Dr. means the balance is
     * negative — e.g. an overdrawn account — Cr. means positive).
     */
    public static BigDecimal parseNumeric(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        boolean negative = false;
        // Bug fix: verified against a real Union Bank of India statement -- its Dr/Cr marker is
        // parenthesized ("50000.00(Cr)", "1627.00(Dr)"), not the bare trailing " Dr"/" Cr" this
        // used to require exclusively (Axis's format). Every amount cell failed to parse and the
        // whole row silently vanished, same failure shape as the earlier Rupee-as-"C" bug -- see
        // TRAILING_DR/TRAILING_CR's own doc comment for why one pattern now covers both forms.
        if (TRAILING_DR.matcher(s).find()) {
            negative = true;
            s = TRAILING_DR.matcher(s).replaceFirst("");
        } else if (TRAILING_CR.matcher(s).find()) {
            s = TRAILING_CR.matcher(s).replaceFirst("");
        }
        s = s.trim();
        // Bug 34 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). The accounting convention for a
        // negative amount -- the WHOLE cell wrapped in parentheses, e.g. "(1,234.00)" or
        // "(Rs. 1,234.00)" -- distinct from TRAILING_DR/TRAILING_CR above, which only strip a
        // parenthesized "(Dr)"/"(Cr)" MARKER as a suffix, not this. Left unhandled, the opening
        // paren survives every strip below and `new BigDecimal(...)` throws, so the cell parses
        // as null -- which for an ordinary transaction row (not just here) is Bug 30's root cause:
        // TransactionNormalizer's amount lookup falls through to the balance column on a null
        // parse, so the row imports with the account's running balance staged as the transaction
        // amount instead of vanishing loudly.
        if (s.length() >= 2 && s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')') {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        s = s.replaceAll("(?i)^\\s*(rs\\.?|inr)\\s*", "").replace("₹", "")
                // Bug fix: verified against a real uploaded HDFC statement -- that PDF's embedded
                // font doesn't map the Rupee glyph to the real Unicode ₹ codepoint at all; PDFBox
                // extracts it as a literal "C" instead (e.g. "+  C 440.00" for what renders on
                // screen as "+ ₹440.00"). Left unstripped, every amount cell in a file with this
                // quirk fails BigDecimal parsing below and the whole row silently vanishes --
                // exactly the "transactions aren't loading" failure mode this fixes. Stripped as
                // a whole token (word-boundary'd, and only when a digit follows once whitespace
                // is ignored) so a real letter elsewhere can never be caught by accident.
                // (?<![A-Za-z0-9]) instead of a plain \b on this side deliberately -- \b would
                // require a word-boundary on BOTH sides of "C", but "C" sits directly against the
                // digit with no separating space in this real file's "C200.00"/"C1,817.02" cells
                // (only some occurrences have a space, e.g. "+  C 440.00"), and \b never fires
                // between two word characters ("C" and "2") in the first place.
                .replaceAll("(?i)(?<![A-Za-z0-9])C(?=\\s*\\d)", "")
                .replace(",", "").trim();
        // Any whitespace still left at this point (e.g. between a sign and the digits, once the
        // currency-glyph artifact above was removed) can't be part of a valid number either way.
        s = s.replaceAll("\\s+", "");
        if (s.isEmpty() || s.equals("-")) return null;
        try {
            BigDecimal value = new BigDecimal(s);
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String maskAccountNumber(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return digits;
        return "••••" + digits.substring(digits.length() - 4);
    }
}
