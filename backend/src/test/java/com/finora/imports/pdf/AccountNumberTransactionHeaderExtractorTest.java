package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberTransactionHeaderExtractorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real ICICI CC.pdf's
    // own transaction table header (y=376.2: Date, SerNo., Transaction Details, Reward, Intl.#,
    // Amount (in`)) and the unlabeled row directly beneath it (y=398.3, x=207.8-285.5 -- the exact
    // x-range of the Date column). Digits are invented per the Synthetic Fixture Policy -- the
    // geometry (header shape, column alignment, row gap) is what this test exercises, not the real
    // document's own number.
    @Test
    void extract_readsTheUnlabeledValueDirectlyUnderTheDateColumn_beforeTheFirstTransaction() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("SerNo.", 262.4f, 280.5f, 376.2f),
                run("Transaction Details", 305.3f, 358.1f, 376.2f),
                run("Reward", 443.2f, 464.7f, 376.2f),
                run("Intl.#", 485.7f, 497.7f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("100200XXXXXX3400", 207.8f, 285.5f, 398.3f),
                run("17/06/2026", 207.8f, 239.1f, 411.7f),
                run("Housingcom Gurgaon IN", 305.3f, 372.0f, 411.7f),
                run("1,652.00", 533.3f, 556.9f, 411.7f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, ctx))
                .isEqualTo("100200XXXXXX3400");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_ACCOUNT_NUMBER_ABOVE_TRANSACTIONS");
    }

    @Test
    void extract_returnsNull_whenTheRowBelowTheHeaderIsAlreadyARealTransaction() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("17/06/2026", 207.8f, 239.1f, 411.7f),
                run("1,652.00", 533.3f, 556.9f, 411.7f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null)).isNull();
    }

    /** A hyphen-separated transaction date ("dd-MM-yyyy", a real format PdfMetadataExtractor.
     *  DATE_FORMATS already supports elsewhere in this corpus, e.g. Kotak's "16-Feb-2026" dates)
     *  matches CARD_NUMBER_VALUE's shape too, since that pattern allows '-' as an internal separator
     *  -- without a date check first, this would be misread as the account number. */
    @Test
    void extract_returnsNull_whenTheRowBelowTheHeaderIsARealTransaction_withAHyphenSeparatedDate() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("17-06-2026", 207.8f, 239.1f, 411.7f),
                run("1,652.00", 533.3f, 556.9f, 411.7f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null)).isNull();
    }

    /** The forward scan must continue past a hyphen-dated transaction row (not stop or misread it)
     *  to find a genuine account number a few rows further down within the same gap window. */
    @Test
    void extract_skipsAHyphenDateRow_toReachARealAccountNumberBelowIt() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("17-06-2026", 207.8f, 239.1f, 383.0f),
                run("100200XXXXXX3400", 207.8f, 285.5f, 398.3f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null))
                .isEqualTo("100200XXXXXX3400");
    }

    @Test
    void extract_returnsNull_whenNoHeaderRowHasBothADateCellAndAnAmountCell() {
        var runs = List.of(
                run("STATEMENT DATE", 79.5f, 149.1f, 193.4f),
                run("July 11, 2026", 81.2f, 143.2f, 205.3f),
                run("100200XXXXXX3400", 207.8f, 285.5f, 398.3f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null)).isNull();
    }

    /** A two-line header (a real "Reward Points"/"Intl.# amount" continuation row, as on the real
     *  ICICI document) must not stop the forward scan before it reaches the actual value row. */
    @Test
    void extract_skipsAWrappedHeaderContinuationLine_toReachTheValueRowBelowIt() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("Points", 445.4f, 462.5f, 383.4f),
                run("amount", 481.2f, 502.2f, 383.4f),
                run("100200XXXXXX3400", 207.8f, 285.5f, 398.3f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null))
                .isEqualTo("100200XXXXXX3400");
    }

    @Test
    void extract_returnsNull_whenTheValueRowIsTooFarBelowTheHeader() {
        var runs = List.of(
                run("Date", 207.8f, 221.0f, 376.2f),
                run("Amount (in`)", 521.9f, 556.9f, 376.2f),
                run("100200XXXXXX3400", 207.8f, 285.5f, 500.0f));

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, null)).isNull();
    }

    @Test
    void extract_returnsNull_onEmptyInput() {
        assertThat(AccountNumberTransactionHeaderExtractor.extract(List.of(), null)).isNull();
    }
}
