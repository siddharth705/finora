package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Column names and the "Transaction Count"/"Transaction Turnover" shape are evidenced against the
 * real HSBC.pdf composite statement in the corpus (its exact printed figures are never reproduced
 * here, only the row shape). Every value in this test is invented.
 */
class ExplicitZeroActivityDetectorTest {

    private static Map<String, String> row(String... kv) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) row.put(kv[i], kv[i + 1]);
        return row;
    }

    @Test
    void true_whenARowLabelledTransactionCountHasZeroOnBothSides() {
        List<Map<String, String>> rows = List.of(
                row("Date", "01Jan2026", "Transaction Details", "BALANCE BROUGHT FORWARD",
                        "Balance", "500.00"),
                row("Transaction Details", "Transaction Count", "Deposits", "0", "Withdrawals", "0"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isTrue();
    }

    /** The real document's own row formation glues unrelated boilerplate onto this row's OTHER
     *  columns (see PdfPreviewGenerator's own comment on why LocatedSection rows are kept
     *  unfiltered) -- the label match has to survive that, since insisting on an exact-equals match
     *  would never fire on the actual evidencing document. */
    @Test
    void true_whenTheLabelIsASubstringAmongOtherGluedText() {
        List<Map<String, String>> rows = List.of(
                row("Transaction Details", "Transaction Count",
                        "Date", "Important Notes: some unrelated disclaimer paragraph follows here",
                        "Deposits", "0", "Withdrawals", "0"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isTrue();
    }

    @Test
    void false_whenEitherSideIsNonZero() {
        List<Map<String, String>> rows = List.of(
                row("Transaction Details", "Transaction Count", "Deposits", "3", "Withdrawals", "0"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isFalse();
    }

    @Test
    void false_whenNeitherFigureIsPresentAtAll() {
        List<Map<String, String>> rows = List.of(
                row("Transaction Details", "Transaction Count"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isFalse();
    }

    @Test
    void false_whenNoRowMentionsTransactionCountAtAll() {
        List<Map<String, String>> rows = List.of(
                row("Date", "01Jan2026", "Transaction Details", "ATM WITHDRAWAL",
                        "Withdrawals", "500.00"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isFalse();
    }

    /** "Transaction Count" alone is not the same claim as this document makes -- a bank that only
     *  ever prints a running total of transactions (no per-direction breakdown) has not stated
     *  zero, and inferring it from a single combined figure would be a guess this detector must
     *  not make. */
    @Test
    void false_whenOnlyOneOfTheTwoDirectionColumnsIsPresent() {
        List<Map<String, String>> rows = List.of(
                row("Transaction Details", "Transaction Count", "Deposits", "0"));

        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(rows)).isFalse();
    }

    @Test
    void false_forAnEmptyRowList() {
        assertThat(ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(List.of())).isFalse();
    }
}
