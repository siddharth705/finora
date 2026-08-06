package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the totals a statement prints about ITSELF — debit total, credit total, and how many of
 * each — so they can be checked against what the parser produced.
 *
 * <p><b>Why this is worth extracting.</b> Everything else the import pipeline verifies is derived
 * from the same rows it is trying to verify. The balance chain compares rows to their own running
 * balance; the statement totals check compares rows to two header fields. This is different in
 * kind: the bank counted its own transactions, and that count was produced by the bank's ledger,
 * not by our parsing of the bank's PDF. A count of 3 against 4 parsed rows is evidence no amount
 * of internally-consistent arithmetic can supply — it catches a duplicated or dropped row even
 * when every balance still chains perfectly.
 *
 * <p><b>The shape it reads.</b> A row of labels followed by a row of values, associated by
 * horizontal overlap rather than by order — on a real HDFC statement "Debit Amount" spans
 * x=226.87–279.31 and its value "538.00" spans 243.35–267.81, sitting inside it, while
 * "Credit Amount" and its value sit inside their own span 90 points away. Order alone would be
 * fragile the moment a grid omits one column; overlap is what actually encodes the association,
 * and it is the same pattern {@link PdfMetadataExtractor} already reads for its own grid fields.
 *
 * <p><b>Deliberately refuses more than it accepts.</b> A transaction table's own header row can
 * carry "Debit Amount"/"Credit Amount" too, and the row under THAT is the first transaction, not a
 * total — reading it would report a single payment as the statement's debit total and then
 * confidently fail a correct import. So a grid is only accepted when it carries a label a
 * transaction table never has: a count, or a total spelled as a total. A statement printing bare
 * unlabelled sums is left unread, which costs a check that was never available anyway; guessing
 * would cost a false accusation, and those are the expensive kind.
 *
 * <p>A static utility rather than an injected component, in the same spirit as {@code CsvParser}
 * and {@code BalanceChainUtil}: positioned runs in, a record out, no collaborators and no state to
 * configure. Making it injectable would have added a constructor parameter to PdfPreviewGenerator
 * and, with it, twenty test files of churn that this feature has no reason to cause.
 */
public final class StatementSummaryExtractor {

    private StatementSummaryExtractor() {}

    /** Runs within this many points of each other vertically are one visual row. */
    private static final float ROW_TOLERANCE = 2.0f;

    /** How far below a label row its values may sit. Generous enough for the ~18pt gap seen in
     *  practice, tight enough not to reach across a blank region into unrelated content. */
    private static final float MAX_VALUE_ROW_GAP = 40.0f;

    private static final List<String> DEBIT_TOTAL_LABELS = List.of(
            "debit amount", "total debit", "total debits", "total debit amount",
            "withdrawal amount", "total withdrawal", "total withdrawals");
    private static final List<String> CREDIT_TOTAL_LABELS = List.of(
            "credit amount", "total credit", "total credits", "total credit amount",
            "deposit amount", "total deposit", "total deposits");
    private static final List<String> DEBIT_COUNT_LABELS = List.of(
            "debit count", "no of debits", "no. of debits", "number of debits", "total debit count");
    private static final List<String> CREDIT_COUNT_LABELS = List.of(
            "credit count", "no of credits", "no. of credits", "number of credits", "total credit count");

    /** Labels a transaction table's header row cannot plausibly carry. One must appear SOMEWHERE in
     *  the document for any grid to be read — see the class comment on why this refuses rather than
     *  guesses. Checked document-wide rather than per row because a real summary block splits its
     *  labels across rows: the HDFC grid prints totals under one label row and counts under
     *  another, and only the second says "Count". Requiring each row to prove itself in isolation
     *  read the counts and silently dropped the totals sitting 40 points above them. */
    private static boolean isUnambiguouslyASummaryLabel(String normalized) {
        return matches(normalized, DEBIT_COUNT_LABELS) || matches(normalized, CREDIT_COUNT_LABELS)
                || normalized.startsWith("total ");
    }

    /**
     * What the statement printed about itself, with every field nullable because a statement may
     * print any subset — counts without totals, totals without counts, or nothing at all.
     */
    public record PrintedSummary(BigDecimal debitTotal, BigDecimal creditTotal,
                                  Integer debitCount, Integer creditCount) {

        public static final PrintedSummary NONE = new PrintedSummary(null, null, null, null);

        /** True when the statement printed nothing this class could read — the caller's cue to
         *  report "not applicable" rather than to compare against absent evidence. */
        public boolean isEmpty() {
            return debitTotal == null && creditTotal == null && debitCount == null && creditCount == null;
        }
    }

    public static PrintedSummary extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static PrintedSummary extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedSummary.NONE;

        // Nothing is read from a document that never says "count" or "total" anywhere. This is the
        // gate that keeps a transaction table's own "Debit Amount"/"Credit Amount" header from
        // being mistaken for a summary grid on statements that carry no summary at all.
        boolean documentHasASummary = runs.stream()
                .anyMatch(t -> isUnambiguouslyASummaryLabel(normalize(t.text())));
        if (!documentHasASummary) return PrintedSummary.NONE;

        List<List<PositionedText>> rows = groupIntoRows(runs);
        Map<String, PositionedText> labelled = new LinkedHashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            List<PositionedText> labelRow = rows.get(i);
            if (labelRow.stream().noneMatch(t -> keyFor(normalize(t.text())) != null)) continue;

            List<PositionedText> valueRow = rowBelow(rows, i);
            if (valueRow == null) continue;

            // The discriminator that makes this safe without needing to know where the summary
            // block sits on the page: a summary's value row is ENTIRELY numeric, and a transaction
            // row never is -- it always carries a date and a description. So even on a statement
            // whose table header literally reads "Debit Amount | Credit Amount", the row beneath it
            // disqualifies itself, while a real summary's "538.00  25,000.00" does not.
            boolean allValuesNumeric = valueRow.stream()
                    .allMatch(t -> CsvParser.parseNumeric(t.text().trim()) != null);
            if (!allValuesNumeric) continue;

            for (PositionedText label : labelRow) {
                PositionedText value = valueUnder(label, valueRow);
                if (value == null) continue;
                String key = keyFor(normalize(label.text()));
                if (key != null) labelled.putIfAbsent(key, value);
            }
        }

        if (labelled.isEmpty()) return PrintedSummary.NONE;
        if (ctx != null) ctx.record("PRINTED_SUMMARY_TOTALS");

        return new PrintedSummary(
                amount(labelled.get("debitTotal")), amount(labelled.get("creditTotal")),
                count(labelled.get("debitCount")), count(labelled.get("creditCount")));
    }

    private static String keyFor(String normalized) {
        // Counts before totals: "total debit count" contains a total-label substring too, and
        // reading it as an amount would put a transaction count where a rupee figure belongs.
        if (matches(normalized, DEBIT_COUNT_LABELS)) return "debitCount";
        if (matches(normalized, CREDIT_COUNT_LABELS)) return "creditCount";
        if (matches(normalized, DEBIT_TOTAL_LABELS)) return "debitTotal";
        if (matches(normalized, CREDIT_TOTAL_LABELS)) return "creditTotal";
        return null;
    }

    /** The value sitting under a label: the run in the value row whose horizontal span overlaps the
     *  label's most. Overlap rather than nearest-centre, so a narrow value under a wide label still
     *  wins over a closer-centred value belonging to the next column along. */
    private static PositionedText valueUnder(PositionedText label, List<PositionedText> valueRow) {
        PositionedText best = null;
        float bestOverlap = 0f;
        for (PositionedText candidate : valueRow) {
            float overlap = Math.min(label.endX(), candidate.endX()) - Math.max(label.x(), candidate.x());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The next visual row below row {@code i} that is close enough to belong to it.
     *
     * <p>The page check is not a refinement. {@code groupIntoRows} sorts by {@code pageIndex}
     * first and then by {@code y}, so the row after the LAST row of page N is the FIRST row of
     * page N+1 -- and because PDFBox's {@code getYDirAdj()} measures downward from the top of
     * each page, {@code y} resets per page. The last row of a page therefore has a large y and
     * the first row of the next has a small one, the subtraction comes out strongly NEGATIVE, and
     * a bare {@code <= MAX_VALUE_ROW_GAP} was trivially satisfied by a row on a different page.
     * Any statement whose summary labels fall at a page break read its debit/credit totals and
     * counts from unrelated text at the top of the following page -- which then went to
     * SummaryTotalsValidator as the document's own printed evidence, so a correct parse could be
     * reported as failing its totals check, or a wrong one could pass. "Below" cannot be
     * expressed as a distance alone once y is page-relative.
     */
    private static List<PositionedText> rowBelow(List<List<PositionedText>> rows, int i) {
        if (i + 1 >= rows.size()) return null;
        List<PositionedText> current = rows.get(i);
        List<PositionedText> next = rows.get(i + 1);
        if (next.get(0).pageIndex() != current.get(0).pageIndex()) return null;
        return (next.get(0).y() - current.get(0).y()) <= MAX_VALUE_ROW_GAP ? next : null;
    }

    private static List<List<PositionedText>> groupIntoRows(List<PositionedText> runs) {
        List<PositionedText> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingInt(PositionedText::pageIndex)
                .thenComparing(PositionedText::y)
                .thenComparing(PositionedText::x));

        List<List<PositionedText>> rows = new ArrayList<>();
        List<PositionedText> current = new ArrayList<>();
        for (PositionedText t : sorted) {
            if (current.isEmpty()
                    || (t.pageIndex() == current.get(0).pageIndex()
                        && Math.abs(t.y() - current.get(0).y()) <= ROW_TOLERANCE)) {
                current.add(t);
            } else {
                rows.add(current);
                current = new ArrayList<>(List.of(t));
            }
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    private static BigDecimal amount(PositionedText t) {
        return t == null ? null : CsvParser.parseNumeric(t.text().trim());
    }

    /** A count is a plain non-negative integer. Anything else -- a rupee figure that drifted under
     *  a count label, a dash standing in for "none" -- is reported as absent rather than coerced. */
    private static Integer count(PositionedText t) {
        if (t == null) return null;
        String raw = t.text().trim().replace(",", "");
        if (!raw.matches("\\d{1,7}")) return null;
        return Integer.valueOf(raw);
    }

    private static boolean matches(String normalized, List<String> labels) {
        return labels.contains(normalized);
    }

    /** Trims the punctuation a grid label collects -- a trailing colon, surrounding whitespace --
     *  without touching the words, so the label lists stay readable as the labels they match. */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim()
                .replaceAll("[:\\s]+$", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
