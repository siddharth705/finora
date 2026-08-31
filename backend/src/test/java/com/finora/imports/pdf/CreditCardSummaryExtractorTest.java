package com.finora.imports.pdf;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a credit-card statement's own billing-summary panel via either of two independent
 * strategies -- GRID (a stacked label-row/value-row grid) and INLINE_LABEL_VALUE (a same-visual-row
 * label-left/value-right layout).
 *
 * <p>The label vocabulary and the two layout shapes tested here were drawn from reading all 6 real
 * credit-card documents' raw positioned text (coordinates intact, not the lossy line-joined
 * auxiliary text) during the Credit Card Direction Evidence Study and its follow-up measurement —
 * never invented in advance. The specific geometry in each test below is invented, per the
 * Synthetic Fixture Policy: it reproduces the SHAPE actually observed (a grid row-merge from a
 * real Axis statement, a same-row layout from a real AU statement), not a copy of either real
 * document's own positions or figures.
 */
class CreditCardSummaryExtractorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText runOnPage(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
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
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
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
    void feesIsOptionalUnlikeThePreviousBalancePurchasesPaymentsAndTotalDue() {
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
    void cashAdvancesIsAlsoOptional() {
        // The real shape found on AU's statement: no "Cash Advances" line printed anywhere on the
        // page at all (the customer had none), not merely a zero value under that label.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.cashAdvances()).isNull();
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void refusesATransactionTableHeaderThatHappensToNameTotalAmountDue() {
        // A transaction row carries a date and a description alongside its amount, so it is never
        // an all-numeric value row -- the same discriminator StatementSummaryExtractor relies on.
        // Also exercises the GRID row-merge recovery path below: "01/07/2026" is date-shaped and
        // "SOME PAYMENT" is neither date- nor amount-shaped, so recovery must refuse this row
        // rather than pull "111.00" out of it.
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
                .isEqualTo(CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE);
    }

    @Test
    void readsNothingFromAnEmptyDocument() {
        assertThat(CreditCardSummaryExtractor.extract(List.of()).hasReconcilableFields()).isFalse();
        assertThat(CreditCardSummaryExtractor.extract(null).hasReconcilableFields()).isFalse();
    }

    // --- GRID row-merge recovery (real shape: a real Axis statement's date-range row and amount
    // row sit ~1.0pt apart in y, close enough that groupIntoRows merges them into one group) ---

    @Test
    void recoversTheAmountFromAGridRowThatGroupIntoRowsMergedWithADateRange() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Statement Period", 150f, 100f, 224f),
                run("Total Amount Due", 50f, 90f, 224f),
                // Merged by groupIntoRows despite being two visually distinct rows: 1.5pt apart,
                // well within ROW_TOLERANCE -- the same gap observed on the real document.
                run("24/06/2026 - 22/07/2026", 150f, 150f, 236.5f),
                run("34,521.90", 55f, 40f, 238.0f),
                // The other three required fields, in a separate clean grid far enough away not to
                // interact with the merge above -- without these, GRID's own result wouldn't be
                // reconcilable and the extractor would (correctly) move on to try INLINE_LABEL_VALUE instead,
                // which would find nothing here and mask whether recovery actually worked.
                run("Previous Balance", 50f, 90f, 400f),
                run("Purchases", 150f, 60f, 400f),
                run("Payments / Credits", 220f, 90f, 400f),
                run("1,000.00", 55f, 40f, 430f),
                run("500.00", 155f, 40f, 430f),
                run("200.00", 225f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue())
                .as("the date range must not block recovery of the genuine amount in the same "
                        + "merged row")
                .isEqualByComparingTo("34521.90");
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void doesNotRecoverAValueFromARowWithUnclassifiableContentAlongsideAnAmount() {
        // "SOME PAYMENT" is neither date-shaped nor amount-shaped -- recovery requires the merged
        // row to be FULLY explained as dates-plus-amounts, so a third, unrecognised kind of content
        // must refuse the whole row rather than guess which number belongs to the label.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 90f, 224f),
                run("24/06/2026", 150f, 80f, 236.5f),
                run("SOME PAYMENT", 250f, 90f, 236.8f),
                run("34,521.90", 55f, 40f, 238.0f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    // --- INLINE_LABEL_VALUE strategy (real shape: a real AU statement's "Bill summary" widget, label left,
    // value right, at a roughly fixed y and a right-hand x offset) ---

    private static List<PositionedText> sameRowSummaryBlock() {
        return new ArrayList<>(List.of(
                run("Opening balance", 355f, 70f, 229.6f),
                run("40,000.00", 518f, 46f, 228.2f),
                run("Total spends", 355f, 53f, 250.9f),
                run("6,000.00", 523f, 41f, 249.4f),
                run("Payments & Refunds", 355f, 86f, 272.1f),
                run("44,000.00", 518f, 46f, 270.7f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));
    }

    @Test
    void readsFieldsFromASameRowLabelLeftValueRightLayout() {
        var summary = CreditCardSummaryExtractor.extract(sameRowSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("40000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("6000.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("44000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("2000.00");
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.INLINE_LABEL_VALUE);
    }

    @Test
    void gridIsTriedBeforeSameRow() {
        // A document exercising the clean GRID shape must never fall through to INLINE_LABEL_VALUE, even
        // though nothing here prevents INLINE_LABEL_VALUE from also matching a stacked grid's labels --
        // GRID's own values are stacked BELOW, not beside, so INLINE_LABEL_VALUE's same-y search would find
        // no candidate to its right and correctly produce nothing for it to compete with.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void refusesToGuessWhenTwoCandidateAmountsCompeteForTheSameLabel() {
        // Two numeric tokens both sit to the right of "Opening balance", both within the same-row
        // y-tolerance -- a genuinely ambiguous case. The nearer one is usually right, but "usually
        // right" is exactly the confident-wrong-guess this strategy exists to avoid.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f),
                run("Opening balance", 355f, 70f, 229.6f),
                run("40,000.00", 518f, 46f, 228.2f),
                run("90,000.00", 518f, 46f, 230.0f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("ambiguous -- must not guess the nearer of two competing candidates")
                .isNull();
    }

    @Test
    void requiresTheCandidateToBeToTheRightOfTheLabelNotJustNearItInY() {
        // A number at the same y but to the LEFT of the label (e.g. a page-margin figure, or the
        // end of the previous line) must never be picked up as this label's value.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("50.00", 10f, 30f, 229.6f),
                run("Opening balance", 355f, 70f, 229.6f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance()).isNull();
    }

    @Test
    void requiresTheCandidateToBeReasonablyCloseInXNotJustAnywhereOnThePage() {
        // Real bug, found verifying against the real corpus: a real Axis statement has an unrelated
        // fee-schedule example elsewhere on the same page (an isolated "Purchase" label followed,
        // much further right, by an unrelated example amount) that trySameRow matched as if it were
        // this statement's own summary field before this distance cap existed. Invented numbers/
        // labels below reproduce the SHAPE of that bug -- a label and a same-page, same-row-ish
        // numeric token separated by an implausibly wide gap -- not the real document's content.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f),
                run("Purchases", 60f, 60f, 600f),
                run("7250", 800f, 30f, 600.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).purchases())
                .as("far enough away that it is almost certainly unrelated content, not this "
                        + "statement's own summary panel")
                .isNull();
    }

    @Test
    void refusesADuplicateLabelRatherThanTakingTheFirstOccurrence() {
        // Real banks repeat summary-style wording in footers or help sections. Two "Opening
        // balance" occurrences, each individually resolving to its OWN single, unambiguous
        // candidate -- but which one is genuinely this statement's own summary field is not
        // decidable from position alone, so neither should win by being scanned first.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Opening balance", 355f, 70f, 229.6f),
                run("10,000.00", 518f, 46f, 228.2f),
                run("Opening balance", 355f, 70f, 700f),
                run("50,000.00", 518f, 46f, 699f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("a repeated label is ambiguous, not first-wins")
                .isNull();
    }

    @Test
    void gridAlsoRefusesADuplicateLabelRatherThanTakingTheFirst() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 220f, 90f, 300f),
                run("Total Amount Due", 320f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 225f, 40f, 330f),
                run("13,000.00", 325f, 40f, 330f),
                // The same label repeated in an unrelated block elsewhere on the page, its own
                // clean grid shape, a different value.
                run("Previous Balance", 50f, 90f, 500f),
                run("50,000.00", 55f, 40f, 530f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("ambiguous for GRID too, not just INLINE_LABEL_VALUE")
                .isNull();
    }

    @Test
    void flagsAConflictWhenTheTwoStrategiesDisagreeOnTheSameField() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // A clean, fully-reconcilable GRID block.
                run("Previous Balance", 50f, 90f, 100f),
                run("Purchases", 150f, 60f, 100f),
                run("Payments / Credits", 220f, 90f, 100f),
                run("Total Amount Due", 320f, 90f, 100f),
                run("10,000.00", 55f, 40f, 130f),
                run("5,000.00", 155f, 40f, 130f),
                run("2,000.00", 225f, 40f, 130f),
                run("10,000.00", 325f, 40f, 130f),
                // An isolated label/value pair elsewhere on the page, independently naming a
                // DIFFERENT total amount due via the INLINE_LABEL_VALUE shape -- an invented
                // fixture reproducing the possibility (not observed on a real document yet) that
                // two genuinely independent readings of the same statement could disagree.
                run("Total Amount Due", 50f, 90f, 600f),
                run("12,000.00", 250f, 40f, 600.5f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.conflictingFields())
                .as("GRID's own equation would otherwise be VERIFIED-able on its own numbers -- "
                        + "the conflict must still be reported")
                .containsExactly("totalAmountDue");
    }

    // --- Page-region selection: real shape found verifying against the actual corpus for BOTH AU
    // and Axis -- in both cases the confounding duplicate/unrelated match was on a DIFFERENT page
    // than the real summary, not just elsewhere on the same page. ---

    @Test
    void aCompletePageWinsOverAWeakerDuplicateOnAnotherPage() {
        // AU's real shape: a full, real "Bill summary" cluster on page 0, and a lone, unrelated
        // repeat of "Opening balance" (a different real-world concept -- likely a rewards-points
        // balance -- with a different number) on page 1. Page 0 covers all four required fields;
        // page 1 covers a lone one. The complete page must win outright, not be blocked by the
        // page-1 repeat the way a same-page duplicate would be.
        List<PositionedText> runs = new ArrayList<>(List.of(
                runOnPage("Opening balance", 355f, 70f, 229.6f, 0),
                runOnPage("40,000.00", 518f, 46f, 228.2f, 0),
                runOnPage("Total spends", 355f, 53f, 250.9f, 0),
                runOnPage("6,000.00", 523f, 41f, 249.4f, 0),
                runOnPage("Payments & Refunds", 355f, 86f, 272.1f, 0),
                runOnPage("44,000.00", 518f, 46f, 270.7f, 0),
                runOnPage("Total amount due", 355f, 74f, 363.2f, 0),
                runOnPage("2,000.00", 523f, 41f, 363.5f, 0),
                // The unrelated repeat, on a different page.
                runOnPage("Opening balance", 30f, 70f, 292.6f, 1),
                runOnPage("8,500.00", 105f, 30f, 292.6f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance())
                .as("page 0's complete cluster must win outright over page 1's lone, weaker match")
                .isEqualByComparingTo("40000.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void neverCombinesFieldsFoundOnDifferentPagesIntoOneAnswer() {
        // Axis's real shape: the genuine billing total lives on page 0; an unrelated fee-schedule
        // example elsewhere in the document (page 2, real document) independently names "Purchase"
        // near an unrelated number. Before page-scoping, each field resolved independently and the
        // two got silently combined into one evidence object as if they belonged together.
        List<PositionedText> runs = new ArrayList<>(List.of(
                runOnPage("Total Amount Due", 50f, 90f, 224f, 0),
                runOnPage("34,521.90", 55f, 40f, 254f, 0),
                // An unrelated label/value pair on a different page.
                runOnPage("Purchase", 74f, 29f, 467.5f, 2),
                runOnPage("7250", 200f, 30f, 468f, 2)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.purchases())
                .as("a field resolved on an unrelated page must never be combined with a different "
                        + "field resolved on the real summary's own page")
                .isNull();
    }

    // ------------------------------------------------- gate loosening (Phase 5, task 1)

    @Test
    void totalAmountDueSurfacesAlone_whenOnlyOneStrategyFoundIt_evenWithoutFullReconciliation() {
        // GRID finds ONLY totalAmountDue on this page (no previous balance, purchases, or payments
        // printed alongside it) -- a real shape: some statements' top summary prints just the
        // headline total next to a due date, with no component breakdown anywhere.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields())
                .as("the other three fields are genuinely absent -- reconciliation must still refuse")
                .isFalse();
    }

    @Test
    void totalAmountDueStaysNull_whenTheTwoStrategiesDisagree() {
        // GRID resolves a value from a clean stacked grid on page 0; INLINE_LABEL_VALUE separately
        // resolves a DIFFERENT value from an unrelated same-row match on page 1 (the shape of a real
        // illustrative worked-example section elsewhere in a statement). Genuine disagreement --
        // per the explicit scope decision, this stays unresolved rather than guessing a winner.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f),
                runOnPage("Total Amount Due", 50f, 100f, 500f, 1),
                runOnPage("9,999.00", 160f, 60f, 500f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
        assertThat(summary.conflictingFields()).contains("totalAmountDue");
    }

    @Test
    void totalAmountDueSurfaces_whenTheTwoStrategiesAgree() {
        // A genuinely different shape per page, each strategy resolving the SAME amount from its own
        // page independently: page 0 is a stacked grid (label y=200, value row y=230 -- GRID's
        // shape, too far apart in y for SAME_ROW's 3pt tolerance); page 1 is a same-row layout
        // (label and value both y=200 -- SAME_ROW's shape; GRID finds nothing there, since there is
        // no second row on that page for rowBelow to pair it with). Both land on the identical
        // figure, so this exercises the TRUE agreement branch, not just "one strategy silent."
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f),
                runOnPage("Total Amount Due", 50f, 100f, 200f, 1),
                runOnPage("13,100.00", 160f, 60f, 200f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.conflictingFields())
                .as("equal values across strategies must never register as a conflict")
                .doesNotContain("totalAmountDue");
    }

    @Test
    void aFullyReconciledDocumentIsUnaffected() {
        // Guards against Task 1 accidentally changing AU's already-passing, already-tested shape.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    // ------------------------------------------------- duplicate-label agreement (Phase 5, task 2)

    @Test
    void aDuplicateLabelIsAcceptedWhenEveryOccurrenceAgrees() {
        // Two occurrences of the same label on one page, same value both times -- a bank printing
        // its own total under two different footnote markers/wordings for the identical figure.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f),
                run("Total Amount Due", 440f, 90f, 400f),
                run("13,000.00", 445f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13000.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void aDuplicateLabelStillRefusesWhenOccurrencesDisagree() {
        // Same shape as above, but the second occurrence's value differs -- must remain refused,
        // unchanged from today's behaviour (this is the existing test this task must not break,
        // made explicit).
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f),
                run("Total Amount Due", 440f, 90f, 400f),
                run("999.00", 445f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
    }
}
