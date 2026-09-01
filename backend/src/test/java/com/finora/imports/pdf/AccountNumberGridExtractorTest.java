package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGridExtractorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real Axis
    // credit.pdf's own account-identity block (label row y=253.00, value row y=266.50): "Credit
    // Card Number" at x=50.50-115.52, its masked value directly below it at x=51.00-115.00. An
    // unrelated same-page row (a debit-alert notice) sits at y=264.00, BETWEEN the label and its
    // real value row -- included here specifically to prove the forward scan skips it rather than
    // stopping at the first row-below-the-label the way PaymentDueDateGridExtractor's simpler
    // rowBelow check would. Digits are invented per the Synthetic Fixture Policy -- the geometry
    // and row-skipping shape are what this test exercises, not the real document's own number.
    @Test
    void extract_readsTheRealAxisCreditCardNumberGrid_skippingAnUnrelatedRowInBetween() {
        var runs = List.of(
                run("Credit Card Number", 50.50f, 115.52f, 253.00f),
                run("Auto-Debit registered on 01/08/2026", 200.00f, 350.00f, 264.00f),
                run("100200******3400", 51.00f, 115.00f, 266.50f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isEqualTo("100200******3400");
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real Axis
    // credit.pdf's OWN second occurrence of its card number -- a same-row label/value pair
    // ("Card No:" at x=78.00-112.01, its value immediately after on the SAME row at x=122.00,
    // y=328.50 for both). Proves the same-row strategy independently of the grid strategy above.
    @Test
    void extract_readsTheRealAxisCardNoSameRowPair() {
        var runs = List.of(
                run("   Card No:", 78.00f, 112.01f, 328.50f),
                run("100200******3400", 122.00f, 186.01f, 328.50f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isEqualTo("100200******3400");
    }

    @Test
    void extract_returnsNull_whenNoCardNumberLabelRowExists() {
        var runs = List.of(
                run("Credit Limit", 45.21f, 121.03f, 233.05f),
                run("100,000.00", 45.21f, 100.00f, 248.00f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheColumnUnderTheLabelIsNotCardNumberShaped() {
        var runs = List.of(
                run("Credit Card Number", 50.50f, 115.52f, 253.00f),
                run("N/A", 51.00f, 90.00f, 266.50f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheValueRowIsTooFarBelow() {
        var runs = List.of(
                run("Credit Card Number", 50.50f, 115.52f, 253.00f),
                run("100200******3400", 51.00f, 115.00f, 400.00f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isNull();
    }

    /** {@link AccountNumberGridExtractor#trySameRow} requires exactly one candidate -- two or more
     *  competing same-row values for one label occurrence is refused rather than guessed at, the
     *  same discipline {@link CreditCardSummaryExtractor#trySameRow} already applies. */
    @Test
    void extract_returnsNull_whenTwoCandidatesCompeteOnTheSameRow() {
        var runs = List.of(
                run("Card No:", 78.00f, 112.01f, 328.50f),
                run("100200******3400", 122.00f, 186.01f, 328.50f),
                run("900800******7600", 200.00f, 264.01f, 328.50f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_onEmptyInput() {
        assertThat(AccountNumberGridExtractor.extract(List.of())).isNull();
    }

    /** "Card No" must not match as a bare prefix of an unrelated longer word -- a "Card Nominee"
     *  field is a genuine, realistic nomination-section label, not a contrived edge case, the same
     *  false-positive class PdfMetadataExtractor's own F22-era fixes already guard against for this
     *  label family. */
    @Test
    void extract_doesNotMatchCardNumberLabel_asABarePrefixOfCardNominee() {
        var runs = List.of(
                run("Card Nominee", 50.50f, 115.52f, 253.00f),
                run("100200******3400", 51.00f, 115.00f, 266.50f));

        assertThat(AccountNumberGridExtractor.extract(runs)).isNull();
    }
}
