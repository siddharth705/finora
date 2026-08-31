package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads a credit-card statement's own billing-summary panel — previous balance, purchases/charges,
 * cash advances, fees, payments/credits, and the total amount due — so a validator can check the
 * bank's own component figures reconcile to the bank's own headline total, without reading a single
 * transaction row.
 *
 * <p><b>Why this is independent of transaction extraction.</b> Every field this reads comes from the
 * same summary panel the bank prints about itself, the same way {@link StatementSummaryExtractor}
 * reads a savings statement's debit/credit totals. A credit-card statement whose transaction TABLE
 * is malformed (see the ICICI CC and HDFC Tata Neu Plus Open Investigations in the architecture
 * doc) can still print a perfectly readable summary panel — this extractor and the validator built
 * on it give real evidence on such a document even when {@code PdfTableLocator} cannot form its
 * transaction table correctly.
 *
 * <p><b>Two independent strategies, one evidence shape.</b> Real credit-card summary panels were
 * found (reading all 6 real documents' raw positioned text, coordinates intact, not the lossy
 * line-joined auxiliary text) to use genuinely different layouts: a stacked label-row/value-row
 * grid (ICICI, Axis's headline total), and a same-visual-row label-left/value-right layout at a
 * fixed offset (AU). These are kept as two separate, independently-testable strategies —
 * {@link #tryGrid} and {@link #trySameRow} — rather than one extractor accreting per-bank special
 * cases. Both always run (never short-circuited on the first to find anything), specifically so
 * they can be cross-checked against each other — see {@link CreditCardSummaryEvidence#conflictingFields()}.
 *
 * <p><b>A field is never combined across pages.</b> Both strategies resolve fields per PAGE first,
 * then pick whichever single page covers the most required fields — see {@link #bestPageEvidence}.
 * Confirmed necessary against two different real documents, not a hypothetical: AU repeats
 * "Opening balance" on a later, unrelated page with a different number, and Axis's real billing
 * total (page 0) and an unrelated fee-schedule example naming "Purchase" (page 2) used to get
 * combined into one answer because each field was resolved independently, page-blind, and simply
 * merged by key. A page is the coarsest region granularity available and was sufficient for both
 * real cases found so far; finer within-page clustering is deliberately not built without a real
 * document that needs it (see the Capability Backlog).
 *
 * <p><b>Region-scoping does not replace conflict detection — confirmed on the same real Axis
 * document.</b> Region-scoping stops fields from two DIFFERENT pages being stitched together; it
 * does nothing about a single wrong page that happens to be internally self-consistent. On Axis,
 * INLINE_LABEL_VALUE's winning page turns out to be its fee-schedule page (page 2), which mentions
 * enough money-shaped labels near enough numbers to out-score the real summary page on its own
 * terms — a coherent-looking but wrong reading, not a cross-page mixing bug. GRID (page 0) still
 * recovers the real total. The two disagree, {@link #conflictsBetween} catches it, and the
 * validator reports {@code WARNING} rather than trusting either page's story. The three defenses
 * this class layers — page-scoping, per-page duplicate refusal, and cross-strategy conflict
 * detection — are each catching a genuinely different failure shape, confirmed against real
 * documents for all three, not stacked defensively without evidence that each one is needed.
 *
 * <p><b>Evidence, not invention.</b> Every label below was drawn from reading real documents in the
 * Credit Card Direction Evidence Study or this follow-up pass — never invented in advance. A
 * document whose layout matches neither strategy (HDFC, whose summary text is corrupted by an
 * unmapped-glyph font issue; ICICI/SBI, degraded by their own already-documented table-formation
 * bugs) returns {@link CreditCardSummaryEvidence#NONE} — the same "refuse rather than guess"
 * discipline {@link StatementSummaryExtractor} already follows, not a defect in this extractor.
 *
 * <p>Reuses {@link StatementSummaryExtractor}'s row-grouping and value-matching position logic
 * (widened to package-private for exactly this reuse) rather than re-implementing it, since that
 * logic already carries one documented, non-obvious bug fix (the page-boundary y-reset case in
 * {@code rowBelow}) that a second implementation could silently regress.
 */
public final class CreditCardSummaryExtractor {

    private CreditCardSummaryExtractor() {}

    /** Wider than {@link StatementSummaryExtractor}'s gap: real credit-card summary panels observed
     *  in the corpus space their label and value rows further apart than a savings statement's grid
     *  does. */
    private static final float MAX_VALUE_ROW_GAP = 60.0f;

    /** How far apart a label and its value may sit vertically for {@link #trySameRow} to treat them
     *  as the same visual row. Real AU offsets observed were 0.3–1.5pt; doubled for margin without
     *  being reckless. */
    private static final float SAME_ROW_Y_TOLERANCE = 3.0f;

    /**
     * How far to the right of a label its value may sit, for {@link #trySameRow}.
     *
     * <p>Based on real AU statement coordinates: the four label/value pairs read from its "Bill
     * summary" widget sit 76.9–115.5pt apart horizontally (label's endX to value's x). This caps at
     * roughly double the observed maximum — enough margin to tolerate real layout variation between
     * banks without being reckless, not a round number picked without a reason.
     *
     * <p>Why this exists at all, not just why 200: without it, a real Axis statement's fee-schedule
     * example elsewhere on the same page ("25th Sep Purchase Db 2% 5000...", invented shape
     * reproduced in {@code CreditCardSummaryExtractorTest}) matched as if "Purchase" were this
     * statement's own summary label. It was caught only because the "all four required fields
     * present" gate happened to also be unmet that time — not because anything actually bounded the
     * search. This constant is that bound.
     */
    private static final float SAME_ROW_MAX_X_DISTANCE = 200.0f;

    /** A date, or the first half of a date range ("24/06/2026 - 22/07/2026") -- deliberately a shape
     *  check, not a full parse. Used only to positively identify "this is a date, not an amount" so
     *  {@link #tryGrid}'s row-merge recovery can tell the two apart; it does not need to understand
     *  the range, only to not mistake it for money. */
    private static final Pattern DATE_SHAPED = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}");

    private static final List<String> PREVIOUS_BALANCE_LABELS = List.of(
            "previous balance", "previous statement dues", "previous statement balance",
            "opening balance");
    private static final List<String> PURCHASES_LABELS = List.of(
            "purchases / charges", "purchases/charges", "purchases", "purchase", "purchases/debit",
            "total spends");
    private static final List<String> CASH_ADVANCE_LABELS = List.of(
            "cash advances", "cash advance");
    private static final List<String> FEES_LABELS = List.of(
            "other debit&charges", "other debit & charges", "finance charges", "fees");
    private static final List<String> PAYMENTS_LABELS = List.of(
            "payments / credits", "payments/credits", "payments and credits", "payments & refunds");
    private static final List<String> TOTAL_DUE_LABELS = List.of(
            "total amount due", "total amount due (payable)", "total payment due");

    /**
     * What a credit-card statement printed about its own billing equation, every field nullable
     * because a statement may print any subset. {@code purchases}/{@code cashAdvances}/{@code fees}
     * are additive charges; {@code paymentsAndCredits} is what reduces the balance.
     * {@code extractionMethod} names which strategy this evidence came from, or is {@code null} for
     * {@link #NONE}. {@code conflictingFields} names any field where GRID and INLINE_LABEL_VALUE
     * both found a value and those values disagreed — populated independently of which strategy's
     * numbers this evidence otherwise carries (see {@link #extract}), because a disagreement between
     * two independent readings is itself evidence something is being misread, whether or not the
     * winning strategy's own result happens to look complete.
     */
    public record CreditCardSummaryEvidence(BigDecimal previousBalance, BigDecimal purchases,
            BigDecimal cashAdvances, BigDecimal fees, BigDecimal paymentsAndCredits,
            BigDecimal totalAmountDue, ExtractionMethod extractionMethod,
            List<String> conflictingFields) {

        /** Named for what each strategy IS, not the shape it happens to exploit today —
         *  {@code INLINE_LABEL_VALUE} may gain other label/value-alignment layouts later without a
         *  rename, the way "SAME_ROW" would have implied only one geometry forever. */
        public enum ExtractionMethod { GRID, INLINE_LABEL_VALUE }

        public static final CreditCardSummaryEvidence NONE =
                new CreditCardSummaryEvidence(null, null, null, null, null, null, null, List.of());

        /** True when the fields this validator needs to reconcile are all present. Fees and
         *  cashAdvances are deliberately not required — many real statements have neither and print
         *  no line for it (confirmed on a real AU statement for cashAdvances specifically: the only
         *  "cash advances" text on its billing-summary page is a generic interest-terms disclaimer,
         *  not a value), and treating an absent charge type as a missing input would make this
         *  NOT_APPLICABLE on the common case rather than the rare one. */
        public boolean hasReconcilableFields() {
            return previousBalance != null && purchases != null
                    && paymentsAndCredits != null && totalAmountDue != null;
        }
    }

    public static CreditCardSummaryEvidence extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static CreditCardSummaryEvidence extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return CreditCardSummaryEvidence.NONE;

        // Same gate StatementSummaryExtractor applies, for the same reason: a document that never
        // prints "Total Amount Due" anywhere has no billing-summary panel for either strategy to
        // misread a transaction table's own header as.
        boolean documentHasATotalDue = runs.stream()
                .anyMatch(t -> matches(stripDecoration(StatementSummaryExtractor.normalize(t.text())), TOTAL_DUE_LABELS));
        if (!documentHasATotalDue) return CreditCardSummaryEvidence.NONE;

        // Both strategies always run, deliberately never short-circuited on the first to find
        // anything -- that is what makes conflictsBetween below possible. hasReconcilableFields(),
        // not "found anything at all", decides which one's numbers this evidence carries: a
        // document can print its headline Total Amount Due in a clean GRID shape while its
        // component breakdown lives elsewhere in a shape only INLINE_LABEL_VALUE can read (a real
        // Axis statement does exactly this). The two are never merged field-by-field even when
        // picking a winner -- combining fields pulled by two different matching approaches risks
        // attributing one strategy's number to another's row.
        CreditCardSummaryEvidence grid = tryGrid(runs);
        CreditCardSummaryEvidence sameRow = trySameRow(runs);
        List<String> conflicts = conflictsBetween(grid, sameRow);

        CreditCardSummaryEvidence chosen;
        if (grid.hasReconcilableFields()) {
            chosen = grid;
            if (ctx != null) ctx.record("CREDIT_CARD_SUMMARY_TOTALS");
        } else if (sameRow.hasReconcilableFields()) {
            chosen = sameRow;
            if (ctx != null) ctx.record("CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE");
        } else {
            chosen = CreditCardSummaryEvidence.NONE;
        }

        if (chosen.totalAmountDue() == null) {
            BigDecimal bestEffort = bestEffortTotalAmountDue(grid, sameRow);
            if (bestEffort != null) {
                chosen = new CreditCardSummaryEvidence(chosen.previousBalance(), chosen.purchases(),
                        chosen.cashAdvances(), chosen.fees(), chosen.paymentsAndCredits(),
                        bestEffort, chosen.extractionMethod(), chosen.conflictingFields());
            }
        }

        if (conflicts.isEmpty()) return chosen;
        return new CreditCardSummaryEvidence(chosen.previousBalance(), chosen.purchases(),
                chosen.cashAdvances(), chosen.fees(), chosen.paymentsAndCredits(),
                chosen.totalAmountDue(), chosen.extractionMethod(), conflicts);
    }

    /**
     * {@code totalAmountDue} alone, independent of whether the other three reconciliation fields
     * are present — a statement can print a clean headline total with no component breakdown
     * anywhere, which {@code hasReconcilableFields()} correctly refuses to reconcile but which is
     * still a real, usable metadata fact. Only when the two strategies agree or one is silent; a
     * genuine disagreement stays null, the same "refuse rather than guess" discipline
     * {@link #conflictsBetween} already applies. Deliberately does NOT prefer one strategy's
     * reading over the other's when they conflict — that would be a precedence rule generalised
     * from a single document's evidence, not yet validated against a second one.
     */
    private static BigDecimal bestEffortTotalAmountDue(CreditCardSummaryEvidence grid,
                                                         CreditCardSummaryEvidence sameRow) {
        BigDecimal g = grid.totalAmountDue();
        BigDecimal s = sameRow.totalAmountDue();
        if (g == null) return s;
        if (s == null) return g;
        return g.compareTo(s) == 0 ? g : null;
    }

    /** Fields where both strategies found a value and those values disagreed. A field only one
     *  strategy read at all is not a conflict — silence from the other strategy is not a second
     *  opinion. */
    private static List<String> conflictsBetween(CreditCardSummaryEvidence a, CreditCardSummaryEvidence b) {
        List<String> conflicts = new ArrayList<>();
        if (disagree(a.previousBalance(), b.previousBalance())) conflicts.add("previousBalance");
        if (disagree(a.purchases(), b.purchases())) conflicts.add("purchases");
        if (disagree(a.cashAdvances(), b.cashAdvances())) conflicts.add("cashAdvances");
        if (disagree(a.fees(), b.fees())) conflicts.add("fees");
        if (disagree(a.paymentsAndCredits(), b.paymentsAndCredits())) conflicts.add("paymentsAndCredits");
        if (disagree(a.totalAmountDue(), b.totalAmountDue())) conflicts.add("totalAmountDue");
        return conflicts;
    }

    private static boolean disagree(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) != 0;
    }

    /**
     * Strategy 1 — a stacked label-row/value-row grid, the same shape
     * {@link StatementSummaryExtractor} reads for savings statements.
     *
     * <p><b>Row-merge recovery.</b> A real Axis statement's date-range row and its amount row sit
     * only ~1.0pt apart in y — close enough that {@code groupIntoRows}' tolerance (tuned for a
     * savings-statement grid) merges them into one group, which then fails the "value row must be
     * entirely numeric" safety check because the date range isn't a number. The fix is NOT to
     * shrink that shared tolerance globally, which would risk breaking every other document that
     * relies on it — it is to recognise, only when a merge like this actually happens, that the
     * date contamination is the reason the row looks non-numeric, not evidence the amounts
     * themselves are untrustworthy, and recover just the amount-shaped subset. Recovery requires
     * BOTH a date-shaped token AND an amount-shaped token to be present, with nothing left over
     * unclassified — an unrecognised third kind of content is exactly the case "refuse rather than
     * guess" exists for, so that case is left alone rather than guessed at.
     *
     * <p><b>Duplicate labels and page regions.</b> A field is accepted only when exactly one label
     * ROW on a given page resolves a value for it — see {@link #onlyUnambiguous} — and only the
     * single page covering the most required fields is used at all; see {@link #bestPageEvidence}.
     */
    private static CreditCardSummaryEvidence tryGrid(List<PositionedText> runs) {
        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        Map<Integer, Map<String, List<PositionedText>>> resolvedByPageAndKey = new TreeMap<>();

        for (int i = 0; i < rows.size(); i++) {
            List<PositionedText> labelRow = rows.get(i);
            if (labelRow.stream().noneMatch(t -> keyFor(StatementSummaryExtractor.normalize(t.text())) != null)) {
                continue;
            }

            List<PositionedText> valueRow = valueRowWithinGap(rows, i, MAX_VALUE_ROW_GAP);
            if (valueRow == null) continue;

            boolean allValuesNumeric = valueRow.stream()
                    .allMatch(t -> CsvParser.parseNumeric(t.text().trim()) != null);
            List<PositionedText> effectiveRow = valueRow;
            if (!allValuesNumeric) {
                List<PositionedText> recovered = amountBearingSubset(valueRow);
                if (recovered == null) continue;
                effectiveRow = recovered;
            }

            int page = labelRow.get(0).pageIndex();
            Map<String, List<PositionedText>> resolvedByKey =
                    resolvedByPageAndKey.computeIfAbsent(page, p -> new LinkedHashMap<>());
            for (PositionedText label : labelRow) {
                PositionedText value = StatementSummaryExtractor.valueUnder(label, effectiveRow);
                if (value == null) continue;
                String key = keyFor(StatementSummaryExtractor.normalize(label.text()));
                if (key != null) {
                    resolvedByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
        }

        return bestPageEvidence(resolvedByPageAndKey, CreditCardSummaryEvidence.ExtractionMethod.GRID);
    }

    /**
     * Unlike {@link StatementSummaryExtractor#rowBelow}, which this class reuses everywhere else
     * and which savings-statement parsing also depends on — deliberately NOT touched here, this
     * scans every subsequent row within {@code maxGap} for the first one this class can actually
     * use as a value row, rather than only ever considering the literal next row.
     *
     * <p>A real statement's billing-summary widget can share a page with an unrelated column of
     * running text (a marketing notice, a footer) whose rows interleave with the widget's own by
     * Y position — the immediate next row can belong to that unrelated column, not the widget.
     * This is a strict superset of {@code rowBelow}'s own behaviour: whenever the immediate next
     * row already qualifies, this returns that exact same row (identical to today), so it can
     * only ever recover cases {@code rowBelow} used to give up on, never change one that already
     * worked.
     */
    private static List<PositionedText> valueRowWithinGap(List<List<PositionedText>> rows, int i, float maxGap) {
        if (i + 1 >= rows.size()) return null;
        int page = rows.get(i).get(0).pageIndex();
        float labelY = rows.get(i).get(0).y();
        for (int j = i + 1; j < rows.size(); j++) {
            List<PositionedText> candidate = rows.get(j);
            if (candidate.get(0).pageIndex() != page) return null;
            if (candidate.get(0).y() - labelY > maxGap) return null;
            boolean allNumeric = candidate.stream()
                    .allMatch(t -> CsvParser.parseNumeric(t.text().trim()) != null);
            if (allNumeric || amountBearingSubset(candidate) != null) return candidate;
        }
        return null;
    }

    /** See {@link #tryGrid}'s own doc comment for when and why this is called. */
    private static List<PositionedText> amountBearingSubset(List<PositionedText> mergedRow) {
        List<PositionedText> dateLike = new ArrayList<>();
        List<PositionedText> amountLike = new ArrayList<>();
        List<PositionedText> neither = new ArrayList<>();
        for (PositionedText t : mergedRow) {
            String text = t.text().trim();
            if (CsvParser.parseNumeric(text) != null) {
                amountLike.add(t);
            } else if (DATE_SHAPED.matcher(text).find()) {
                dateLike.add(t);
            } else {
                neither.add(t);
            }
        }
        return (!dateLike.isEmpty() && !amountLike.isEmpty() && neither.isEmpty()) ? amountLike : null;
    }

    /**
     * Strategy 2 — a same-visual-row label-left/value-right layout (AU's real "Bill summary"
     * widget): the value for a label is not below it, it is beside it, at a roughly fixed y and an
     * x somewhere to the right.
     *
     * <p>Deliberately does not use {@code groupIntoRows} at all -- that grouping is built for a
     * single table's rows and, on a genuinely multi-column dashboard layout like this one, would
     * interleave unrelated columns into the same "row" in a way neither strategy could safely
     * untangle. Each label is matched independently instead: search the whole page (same page only,
     * for the same page-boundary reason {@code rowBelow} already documents) for amount-shaped
     * tokens within {@link #SAME_ROW_Y_TOLERANCE} of the label's own y, to its right, and within
     * {@link #SAME_ROW_MAX_X_DISTANCE} of it. Exactly one such candidate is required for a given
     * label occurrence -- zero means no match for that occurrence, and two or more competing
     * candidates means it is refused rather than guessed at.
     *
     * <p><b>Duplicate labels and page regions.</b> Real statements repeat summary-style wording in
     * footers, help sections, or unrelated example tables elsewhere in the document -- a field is
     * accepted only when exactly one label OCCURRENCE on a given page resolves a value for it, even
     * if a repeat would have resolved to the same number, and only the single page covering the
     * most required fields is used at all; see {@link #onlyUnambiguous} and
     * {@link #bestPageEvidence}. Which occurrence is genuinely this statement's own summary field is
     * not decidable from position alone within a page either, so both safeguards apply together.
     */
    private static CreditCardSummaryEvidence trySameRow(List<PositionedText> runs) {
        Map<Integer, Map<String, List<PositionedText>>> resolvedByPageAndKey = new TreeMap<>();

        for (PositionedText label : runs) {
            String key = keyFor(StatementSummaryExtractor.normalize(label.text()));
            if (key == null) continue;

            List<PositionedText> candidates = runs.stream()
                    .filter(t -> t.pageIndex() == label.pageIndex())
                    .filter(t -> t.x() > label.endX())
                    .filter(t -> t.x() - label.endX() <= SAME_ROW_MAX_X_DISTANCE)
                    .filter(t -> Math.abs(t.y() - label.y()) <= SAME_ROW_Y_TOLERANCE)
                    .filter(t -> CsvParser.parseNumeric(t.text().trim()) != null)
                    .sorted(Comparator.comparingDouble(t -> Math.abs(t.y() - label.y())))
                    .toList();

            if (candidates.size() == 1) {
                resolvedByPageAndKey.computeIfAbsent(label.pageIndex(), p -> new LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>()).add(candidates.get(0));
            }
            // Zero candidates for THIS occurrence: try the next label. Two or more competing
            // candidates for this occurrence: refused right here, never added.
        }

        return bestPageEvidence(resolvedByPageAndKey, CreditCardSummaryEvidence.ExtractionMethod.INLINE_LABEL_VALUE);
    }

    /** Accepts a key only when exactly one label occurrence resolved a value for it, OR when more
     *  than one did and every occurrence resolved to the IDENTICAL amount — redundancy (the same
     *  figure printed twice under different wording or footnote markers), not ambiguity.
     *  Occurrences that disagree are still refused, unchanged: two different numbers under one
     *  label is real ambiguity, which this class already refuses rather than guesses at
     *  everywhere else. Shared by both strategies so this is one rule, not two that could drift
     *  apart. */
    private static Map<String, PositionedText> onlyUnambiguous(Map<String, List<PositionedText>> resolvedByKey) {
        Map<String, PositionedText> labelled = new LinkedHashMap<>();
        for (Map.Entry<String, List<PositionedText>> entry : resolvedByKey.entrySet()) {
            List<PositionedText> occurrences = entry.getValue();
            if (occurrences.size() == 1 || allOccurrencesAgree(occurrences)) {
                labelled.put(entry.getKey(), occurrences.get(0));
            }
        }
        return labelled;
    }

    private static boolean allOccurrencesAgree(List<PositionedText> occurrences) {
        BigDecimal first = amount(occurrences.get(0));
        if (first == null) return false;
        for (PositionedText t : occurrences) {
            BigDecimal a = amount(t);
            if (a == null || a.compareTo(first) != 0) return false;
        }
        return true;
    }

    /**
     * Picks the single page whose own (already deduplicated) resolutions cover the most of the
     * four required fields, and builds evidence from that page alone -- never from two pages
     * combined. Ties favor the earliest page ({@code resolvedByPageAndKey} is a {@link TreeMap},
     * so pages are visited in ascending order and the first page to reach a given score keeps it).
     * A page that resolved fields but covers none of the four required ones still loses to a page
     * that resolved even one required field, on the reasoning that a page contributing zero
     * required fields is not a real competing candidate for the summary at all.
     */
    private static CreditCardSummaryEvidence bestPageEvidence(
            Map<Integer, Map<String, List<PositionedText>>> resolvedByPageAndKey,
            CreditCardSummaryEvidence.ExtractionMethod method) {
        Map<String, PositionedText> best = Map.of();
        int bestScore = 0;
        for (Map<String, List<PositionedText>> perPage : resolvedByPageAndKey.values()) {
            Map<String, PositionedText> labelled = onlyUnambiguous(perPage);
            int score = requiredFieldCount(labelled);
            if (score > bestScore) {
                bestScore = score;
                best = labelled;
            }
        }

        if (best.isEmpty()) return CreditCardSummaryEvidence.NONE;
        return new CreditCardSummaryEvidence(
                amount(best.get("previousBalance")), amount(best.get("purchases")),
                amount(best.get("cashAdvances")), amount(best.get("fees")),
                amount(best.get("paymentsAndCredits")), amount(best.get("totalAmountDue")),
                method, List.of());
    }

    private static int requiredFieldCount(Map<String, PositionedText> labelled) {
        int count = 0;
        if (labelled.containsKey("previousBalance")) count++;
        if (labelled.containsKey("purchases")) count++;
        if (labelled.containsKey("paymentsAndCredits")) count++;
        if (labelled.containsKey("totalAmountDue")) count++;
        return count;
    }

    private static String keyFor(String normalized) {
        String stripped = stripDecoration(normalized);
        if (matches(stripped, PREVIOUS_BALANCE_LABELS)) return "previousBalance";
        if (matches(stripped, PURCHASES_LABELS)) return "purchases";
        if (matches(stripped, CASH_ADVANCE_LABELS)) return "cashAdvances";
        if (matches(stripped, FEES_LABELS) || stripped.startsWith("fee & charges")) return "fees";
        if (matches(stripped, PAYMENTS_LABELS)) return "paymentsAndCredits";
        if (matches(stripped, TOTAL_DUE_LABELS)) return "totalAmountDue";
        return null;
    }

    /** Some statements print a footnote marker before an otherwise-exact label, and/or a trailing
     *  parenthetical annotation after it (a currency-symbol placeholder, an abbreviation).
     *  Stripped before matching against this class's own fixed, curated label lists — this can
     *  only ever recognise MORE of what was already an exact match one layer down; it never
     *  introduces fuzzy matching or a new false-positive category, since the stripped result
     *  still has to equal one of the fixed strings exactly. */
    private static String stripDecoration(String normalized) {
        String s = normalized;
        while (s.startsWith("*")) s = s.substring(1);
        return s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    private static boolean matches(String normalized, List<String> labels) {
        return labels.contains(normalized);
    }

    private static BigDecimal amount(PositionedText t) {
        return t == null ? null : CsvParser.parseNumeric(t.text().trim());
    }
}
