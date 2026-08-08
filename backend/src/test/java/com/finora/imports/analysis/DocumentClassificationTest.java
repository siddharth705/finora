package com.finora.imports.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.finora.imports.analysis.DocumentClassification.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 16-statement corpus, measured on 2026-08-08, is the fixture set.
 *
 * <p>Pinning real measurements rather than invented ones is the point: this class exists because two
 * successive hypotheses about that corpus were wrong, and both were wrong because they rested on a
 * convenient proxy instead of a named signal. Encoding the actual figures means a future change to
 * the classifier has to say out loud which real statement it reclassifies.
 *
 * <p>All numbers below come from PDFBox (pages, characters) and the import pipeline's own diagnostic
 * (positioned runs, sections, rows, rule outcomes). No statement's expected transaction count is
 * known yet, so every corpus fixture passes {@code null} for it -- deriving one from today's row
 * count would make the current parser the definition of correct.
 */
class DocumentClassificationTest {

    private static Signals signals(int pages, int chars, int runs, int sections, int rows,
                                   Map<String, String> rules, Integer expected) {
        return new Signals(pages, chars, runs, sections, rows, rules, expected);
    }

    private static Map<String, String> clean() {
        return Map.of("BALANCE_CHAIN", "VERIFIED", "STATEMENT_TOTALS", "VERIFIED",
                "COLUMN_AMBIGUITY", "VERIFIED");
    }

    // ---------------------------------------------------------------- the corpus, as measured

    @Test
    @DisplayName("HSBC: 4 pages and 12,760 characters yielding one row reads as incomplete")
    void hsbc() {
        // Every applicable rule passes here, which is precisely the problem this class was written
        // for -- under the old signal alone HSBC is indistinguishable from a correct import.
        assertThat(of(signals(4, 12760, 238, 1, 1, clean(), null))).isEqualTo(PARSED_INCOMPLETE);
    }

    @Test
    @DisplayName("ICICI CC: 9 pages, 3 rows -- incomplete despite passing every rule")
    void iciciCreditCard() {
        assertThat(of(signals(9, 12413, 486, 1, 3, clean(), null))).isEqualTo(PARSED_INCOMPLETE);
    }

    @Test
    @DisplayName("Bandhan: 7 pages, 3 rows AND a failed balance chain -- reconciliation wins on severity")
    void bandhan() {
        // Both conditions hold. Wrong money is ranked above too little money deliberately, so this
        // asserts the ordering rather than just the outcome.
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "FAILED",
                "STATEMENT_TOTALS", "VERIFIED", "COLUMN_AMBIGUITY", "VERIFIED");
        assertThat(of(signals(7, 8935, 0, 1, 3, rules, null)))
                .isEqualTo(PARSED_RECONCILIATION_FAILED);
    }

    @Test
    @DisplayName("CBI: text positioned fine, zero rows -- unsupported layout, cause not asserted")
    void cbi() {
        Map<String, String> rules = Map.of("COLUMN_AMBIGUITY", "WARNING");
        assertThat(of(signals(9, 16198, 1735, 1, 0, rules, null))).isEqualTo(LAYOUT_UNSUPPORTED);
    }

    @Test
    @DisplayName("ICICI Saving: same classification as CBI, different underlying problem")
    void iciciSaving() {
        // 1,545 chars/page but only 158 positioned runs -- the text exists and is lost before
        // positioning, unlike CBI. Both land on LAYOUT_UNSUPPORTED on purpose: the signals here
        // cannot support asserting which stage failed, and guessing would misdirect the fix.
        assertThat(of(signals(2, 3091, 158, 1, 0, Map.of("COLUMN_AMBIGUITY", "VERIFIED"), null)))
                .isEqualTo(LAYOUT_UNSUPPORTED);
    }

    @ParameterizedTest(name = "{0} classifies as {6}")
    @CsvSource({
            // name,                pages, chars, runs,  sec, rows, expected classification
            "canara,                   10,  9939,   630,  1,   58, PARSED_COMPLETE",
            "new kotak,                  3,  8799,   750,  1,  109, PARSED_COMPLETE",
            "Union Bank,                 2,  2260,   200,  1,   19, PARSED_COMPLETE",
            "Axis credit,                3, 16678,   900,  2,  108, PARSED_COMPLETE",
            // 2 rows from 2 pages and 4 from 2: NOT flagged. Small statements are allowed to be
            // small, and a heuristic that caught these would be one tuned to a desired answer.
            "HDFC credit,                2,  5266,   204,  2,    2, PARSED_COMPLETE",
            "Manas_HDFC,                 2,  2079,   137,  1,    4, PARSED_COMPLETE",
    })
    void corpusStatementsThatLookComplete(String name, int pages, int chars, int runs,
                                          int sections, int rows, DocumentClassification expected) {
        assertThat(of(signals(pages, chars, runs, sections, rows, clean(), null)))
                .as("%s", name)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} classifies as PARSED_RECONCILIATION_FAILED")
    @CsvSource({
            "Mann HDFC,                 39, 87930, 11298,  1, 360",
            "Acct Statement_6137,       24, 56025,  2400,  1, 243",
            "PNBONE_STMT,                3,  6029,   400,  1,  61",
    })
    void corpusStatementsWhoseMoneyDoesNotReconcile(String name, int pages, int chars, int runs,
                                                    int sections, int rows) {
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "FAILED",
                "STATEMENT_TOTALS", "FAILED", "COLUMN_AMBIGUITY", "VERIFIED");
        assertThat(of(signals(pages, chars, runs, sections, rows, rules, null)))
                .as("%s -- rows extracted but totals disagree", name)
                .isEqualTo(PARSED_RECONCILIATION_FAILED);
    }

    // ---------------------------------------------------------------- the rows < pages boundary

    @ParameterizedTest(name = "{0} rows from {1} pages -> {2}")
    @CsvSource({
            "1, 4, PARSED_INCOMPLETE",   // HSBC
            "3, 9, PARSED_INCOMPLETE",   // ICICI CC
            "4, 5, PARSED_INCOMPLETE",   // one below: still flagged
            "5, 5, PARSED_COMPLETE",     // exactly equal: NOT flagged
            "6, 5, PARSED_COMPLETE",     // above
            "2, 2, PARSED_COMPLETE",     // HDFC credit
    })
    void theRowsPerPageBoundaryIsExact(int rows, int pages, DocumentClassification expected) {
        assertThat(of(signals(pages, pages * 2000, pages * 100, 1, rows, clean(), null)))
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------- ground truth outranks it

    @Test
    @DisplayName("ground truth calls a statement incomplete even when rows exceed pages")
    void groundTruthDetectsIncompletenessTheHeuristicCannot() {
        // 40 rows from 3 pages passes the heuristic easily. Only ground truth catches that the
        // statement actually held 120 -- which is why the heuristic is a stopgap, not the mechanism.
        assertThat(of(signals(3, 9000, 800, 1, 40, clean(), 120))).isEqualTo(PARSED_INCOMPLETE);
    }

    @Test
    @DisplayName("ground truth confirming completeness silences the heuristic")
    void groundTruthCanClearAStatementTheHeuristicWouldFlag() {
        // A genuine one-transaction statement across four pages. The heuristic would call this
        // incomplete; established ground truth of 1 says otherwise and must win.
        assertThat(of(signals(4, 12000, 300, 1, 1, clean(), 1))).isEqualTo(PARSED_COMPLETE);
    }

    // ---------------------------------------------------------------- scanned: synthetic only

    @Test
    @DisplayName("SCANNED_OCR_REQUIRED needs zero extractable text, and no real statement reaches it")
    void scannedIsReachableOnlyWithNoTextAtAll() {
        assertThat(of(signals(6, 0, 0, 0, 0, Map.of(), null))).isEqualTo(SCANNED_OCR_REQUIRED);
    }

    @Test
    @DisplayName("the lowest-density real statement is NOT treated as scanned")
    void lowTextDensityAloneIsNotScanned() {
        // canara: 993 chars/page, the thinnest in the corpus, and it parses 58 rows correctly. This
        // pins the reason there is no non-zero threshold -- any cutoff above zero that caught a
        // scanned document would be a number chosen to sort today's files, and this is the statement
        // it would misclassify first.
        assertThat(of(signals(10, 9939, 630, 1, 58, clean(), null))).isEqualTo(PARSED_COMPLETE);
    }

    @Test
    void aDocumentWithNoPagesIsInvalidRatherThanScanned() {
        assertThat(of(signals(0, 0, 0, 0, 0, Map.of(), null))).isEqualTo(DOCUMENT_INVALID);
    }

    // ------------------------------------------- the signals survive the label (Bandhan)

    /**
     * The reason {@code suspectedIncompleteByPageRatio()} exists. Bandhan satisfies two conditions at
     * once, severity ordering reports only the more severe one, and without this accessor the second
     * would be lost -- plausibly the one that explains the first, since totals cannot reconcile if
     * rows are missing.
     */
    @Test
    @DisplayName("Bandhan: labelled by reconciliation, yet still readable as a completeness suspect")
    void bandhanKeepsItsCompletenessSignalDespiteTheReconciliationLabel() {
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "FAILED",
                "STATEMENT_TOTALS", "VERIFIED", "COLUMN_AMBIGUITY", "VERIFIED");
        Signals s = signals(7, 8935, 0, 1, 3, rules, null);

        assertThat(of(s)).isEqualTo(PARSED_RECONCILIATION_FAILED);
        assertThat(s.suspectedIncompleteByPageRatio())
                .as("3 rows from 7 pages must stay visible for Phase 3 even though the label is reconciliation")
                .isTrue();
    }

    @Test
    @DisplayName("a genuinely complete statement carries no completeness suspicion")
    void completeStatementsDoNotRaiseTheRatioSignal() {
        assertThat(signals(10, 9939, 630, 1, 58, clean(), null).suspectedIncompleteByPageRatio())
                .isFalse();
    }

    @Test
    @DisplayName("the ratio signal is independent of the label, in both directions")
    void theRatioSignalIsReportedEvenWhereItDidNotDecideTheLabel() {
        // Zero rows: LAYOUT_UNSUPPORTED, and the ratio predicate deliberately does NOT fire, because
        // "no rows at all" is a different observation from "fewer rows than pages".
        Signals cbi = signals(9, 16198, 1735, 1, 0, Map.of("COLUMN_AMBIGUITY", "WARNING"), null);
        assertThat(of(cbi)).isEqualTo(LAYOUT_UNSUPPORTED);
        assertThat(cbi.suspectedIncompleteByPageRatio()).isFalse();
    }

    @Test
    void rowsPerPageAndGroundTruthFlagAreReportable() {
        Signals s = signals(4, 12760, 238, 1, 1, clean(), null);
        assertThat(s.rowsPerPage()).isZero();
        assertThat(s.charsPerPage()).isEqualTo(3190);
        assertThat(s.hasGroundTruth()).isFalse();
        assertThat(signals(4, 12760, 238, 1, 1, clean(), 1).hasGroundTruth()).isTrue();
    }

    // ---------------------------------------------------------------- ordering

    @Test
    @DisplayName("zero rows outranks a failing rule: there is nothing for a rule to be about")
    void zeroRowsWinsOverRuleFailures() {
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "FAILED", "COLUMN_AMBIGUITY", "WARNING");
        assertThat(of(signals(9, 16198, 1735, 1, 0, rules, null))).isEqualTo(LAYOUT_UNSUPPORTED);
    }

    @Test
    @DisplayName("reconciliation failure outranks column ambiguity")
    void reconciliationOutranksAmbiguity() {
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "FAILED", "COLUMN_AMBIGUITY", "WARNING");
        assertThat(of(signals(5, 10000, 500, 1, 50, rules, null)))
                .isEqualTo(PARSED_RECONCILIATION_FAILED);
    }

    @Test
    @DisplayName("a rule reporting NOT_APPLICABLE is not a failure")
    void notApplicableIsNotAFailure() {
        Map<String, String> rules = Map.of("BALANCE_CHAIN", "NOT_APPLICABLE",
                "STATEMENT_TOTALS", "NOT_APPLICABLE", "COLUMN_AMBIGUITY", "NOT_APPLICABLE");
        assertThat(of(signals(5, 10000, 500, 1, 50, rules, null))).isEqualTo(PARSED_COMPLETE);
    }

    @Test
    @DisplayName("charsPerPage never divides by zero")
    void charsPerPageIsSafeForAPagelessDocument() {
        assertThat(signals(0, 500, 0, 0, 0, Map.of(), null).charsPerPage()).isZero();
    }
}
