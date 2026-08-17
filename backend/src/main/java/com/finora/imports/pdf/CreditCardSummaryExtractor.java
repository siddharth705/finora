package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Evidence, not invention.</b> The label vocabulary below was drawn from reading all 6 real
 * credit-card documents in the corpus during the Credit Card Direction Evidence Study — not
 * invented in advance. Three of those six (Axis, which spells the equation as prose rather than a
 * label/value grid; HDFC, whose summary panel text is corrupted by an unmapped-glyph font issue;
 * and any document whose grid shape doesn't match a clean label-row/value-row pair) are expected to
 * return {@link PrintedCreditCardSummary#NONE} — that is the same "refuse rather than guess"
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

    private static final List<String> PREVIOUS_BALANCE_LABELS = List.of(
            "previous balance", "previous statement dues", "previous statement balance");
    private static final List<String> PURCHASES_LABELS = List.of(
            "purchases / charges", "purchases/charges", "purchases", "purchase", "purchases/debit");
    private static final List<String> CASH_ADVANCE_LABELS = List.of(
            "cash advances", "cash advance");
    private static final List<String> FEES_LABELS = List.of(
            "other debit&charges", "other debit & charges", "finance charges", "fees");
    private static final List<String> PAYMENTS_LABELS = List.of(
            "payments / credits", "payments/credits", "payments and credits");
    private static final List<String> TOTAL_DUE_LABELS = List.of(
            "total amount due", "total amount due (payable)", "total payment due");

    /**
     * What a credit-card statement printed about its own billing equation, every field nullable
     * because a statement may print any subset. {@code purchases}/{@code cashAdvances}/{@code fees}
     * are additive charges; {@code paymentsAndCredits} is what reduces the balance.
     */
    public record PrintedCreditCardSummary(BigDecimal previousBalance, BigDecimal purchases,
            BigDecimal cashAdvances, BigDecimal fees, BigDecimal paymentsAndCredits,
            BigDecimal totalAmountDue) {

        public static final PrintedCreditCardSummary NONE =
                new PrintedCreditCardSummary(null, null, null, null, null, null);

        /** True when the fields this validator needs to reconcile are all present. Fees is
         *  deliberately not required -- many real statements charge none and print no line for it,
         *  and treating an absent fees field as a missing input would make this NOT_APPLICABLE on
         *  the common case rather than the rare one. */
        public boolean hasReconcilableFields() {
            return previousBalance != null && purchases != null && cashAdvances != null
                    && paymentsAndCredits != null && totalAmountDue != null;
        }
    }

    public static PrintedCreditCardSummary extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static PrintedCreditCardSummary extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedCreditCardSummary.NONE;

        // Same gate StatementSummaryExtractor applies, for the same reason: a document that never
        // prints "Total Amount Due" anywhere has no billing-summary panel for this to misread a
        // transaction table's own header as.
        boolean documentHasATotalDue = runs.stream()
                .anyMatch(t -> matches(StatementSummaryExtractor.normalize(t.text()), TOTAL_DUE_LABELS));
        if (!documentHasATotalDue) return PrintedCreditCardSummary.NONE;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        Map<String, PositionedText> labelled = new LinkedHashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            List<PositionedText> labelRow = rows.get(i);
            if (labelRow.stream().noneMatch(t -> keyFor(StatementSummaryExtractor.normalize(t.text())) != null)) {
                continue;
            }

            List<PositionedText> valueRow = StatementSummaryExtractor.rowBelow(rows, i, MAX_VALUE_ROW_GAP);
            if (valueRow == null) continue;

            // Same discriminator as StatementSummaryExtractor: a real transaction row always carries
            // a date or description alongside its amount, so a value row that is entirely numeric
            // cannot be one.
            boolean allValuesNumeric = valueRow.stream()
                    .allMatch(t -> CsvParser.parseNumeric(t.text().trim()) != null);
            if (!allValuesNumeric) continue;

            for (PositionedText label : labelRow) {
                PositionedText value = StatementSummaryExtractor.valueUnder(label, valueRow);
                if (value == null) continue;
                String key = keyFor(StatementSummaryExtractor.normalize(label.text()));
                if (key != null) labelled.putIfAbsent(key, value);
            }
        }

        if (labelled.isEmpty()) return PrintedCreditCardSummary.NONE;
        if (ctx != null) ctx.record("CREDIT_CARD_SUMMARY_TOTALS");

        return new PrintedCreditCardSummary(
                amount(labelled.get("previousBalance")), amount(labelled.get("purchases")),
                amount(labelled.get("cashAdvances")), amount(labelled.get("fees")),
                amount(labelled.get("paymentsAndCredits")), amount(labelled.get("totalAmountDue")));
    }

    private static String keyFor(String normalized) {
        if (matches(normalized, PREVIOUS_BALANCE_LABELS)) return "previousBalance";
        if (matches(normalized, PURCHASES_LABELS)) return "purchases";
        if (matches(normalized, CASH_ADVANCE_LABELS)) return "cashAdvances";
        if (matches(normalized, FEES_LABELS)) return "fees";
        if (matches(normalized, PAYMENTS_LABELS)) return "paymentsAndCredits";
        if (matches(normalized, TOTAL_DUE_LABELS)) return "totalAmountDue";
        return null;
    }

    private static boolean matches(String normalized, List<String> labels) {
        return labels.contains(normalized);
    }

    private static BigDecimal amount(PositionedText t) {
        return t == null ? null : CsvParser.parseNumeric(t.text().trim());
    }
}
