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
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    );

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
     *  rather than throwing (the footer-notes case, where a row may have far fewer cells). */
    public Map<String, String> zipRow(String[] headerRow, String[] cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int c = 0; c < headerRow.length; c++) {
            String key = headerRow[c] == null ? "" : headerRow[c].trim();
            row.put(key, c < cells.length ? cells[c] : null);
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
     */
    public static String normalizeHeaderCell(String cell) {
        if (cell == null) return "";
        String s = cell.trim().toLowerCase();
        s = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
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

    public static LocalDate parseDate(String raw) {
        String withoutTime = TRAILING_TIME.matcher(raw).replaceFirst("");
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(withoutTime, fmt); } catch (Exception ignored) {}
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
        String lower = s.toLowerCase();
        if (lower.endsWith("cr") || lower.endsWith("cr.")) return true;
        if (lower.endsWith("dr") || lower.endsWith("dr.")) return false;
        if (s.startsWith("+")) return true;
        return null;
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
        String lower = s.toLowerCase();
        if (lower.endsWith("dr") || lower.endsWith("dr.")) {
            negative = true;
            s = s.replaceAll("(?i)\\s*dr\\.?$", "");
        } else if (lower.endsWith("cr") || lower.endsWith("cr.")) {
            s = s.replaceAll("(?i)\\s*cr\\.?$", "");
        }
        s = s.replaceAll("(?i)^\\s*(rs\\.?|inr)\\s*", "").replace("₹", "").replace(",", "").trim();
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
