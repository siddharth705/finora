package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a credit-card statement's own billing-summary panel.
 *
 * <p>The label vocabulary matched here was drawn from the Credit Card Direction Evidence Study
 * (see the architecture doc) reading all 6 real credit-card documents in the corpus — not invented
 * in advance. The geometry below is invented, per the Synthetic Fixture Policy: a clean label-row/
 * value-row grid in the shape actually observed (a wider label/value gap than a savings statement's
 * grid), not a copy of any real document's positions or figures.
 */
class CreditCardSummaryExtractorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** A clean, invented billing-summary grid carrying all six fields this extractor reads. */
    private static List<PositionedText> cleanSummaryBlock() {
        return new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Cash Advances", 220f, 70f, 300f),
                run("Fees", 300f, 30f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("0.00", 230f, 30f, 330f),
                run("100.00", 305f, 30f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,100.00", 445f, 40f, 330f)));
    }

    @Test
    void readsAllSixFieldsFromACleanLabelValueGrid() {
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("5000.00");
        assertThat(summary.cashAdvances()).isEqualByComparingTo("0.00");
        assertThat(summary.fees()).isEqualByComparingTo("100.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("2000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void matchesEachValueToTheLabelAboveItRatherThanByOrder() {
        // Previous Balance sits leftmost with the largest printed value (10,000.00); reading by
        // order rather than position would put it under Purchases instead.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("5000.00");
    }

    @Test
    void feesIsOptionalUnlikeTheOtherFiveFields() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Cash Advances", 220f, 70f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("0.00", 230f, 30f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.fees()).isNull();
        assertThat(summary.hasReconcilableFields())
                .as("fees is not one of the fields this needs to attempt a reconciliation")
                .isTrue();
    }

    @Test
    void refusesATransactionTableHeaderThatHappensToNameTotalAmountDue() {
        // A transaction row carries a date and a description alongside its amount, so it is never
        // an all-numeric value row -- the same discriminator StatementSummaryExtractor relies on.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Narration", 120f, 40f, 100f),
                run("Total Amount Due", 200f, 90f, 100f),
                run("01/07/2026", 50f, 40f, 120f),
                run("SOME PAYMENT", 120f, 60f, 120f),
                run("111.00", 200f, 30f, 120f)));
        runs.addAll(cleanSummaryBlock());

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    }

    @Test
    void readsNothingFromADocumentThatNeverPrintsATotalAmountDue() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Amount", 200f, 30f, 100f),
                run("01/07/2026", 50f, 40f, 120f),
                run("111.00", 200f, 30f, 120f)));

        assertThat(CreditCardSummaryExtractor.extract(runs))
                .isEqualTo(CreditCardSummaryExtractor.PrintedCreditCardSummary.NONE);
    }

    @Test
    void readsNothingFromAnEmptyDocument() {
        assertThat(CreditCardSummaryExtractor.extract(List.of()).hasReconcilableFields()).isFalse();
        assertThat(CreditCardSummaryExtractor.extract(null).hasReconcilableFields()).isFalse();
    }
}
