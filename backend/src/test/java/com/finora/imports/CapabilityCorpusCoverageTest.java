package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2, item 1 — the corpus gate.
 *
 * <p>The charter's gate has two halves: <em>the list of layouts we claim to support exists in
 * writing</em>, and <em>every one of them has a trace</em>. The first half already existed —
 * {@link CapabilityCoverageService#KNOWN_CAPABILITIES}, hand-maintained on purpose. Nothing
 * connected it to the second half, so "coverage" was an impression rather than a number.
 *
 * <p><b>What the number is, precisely.</b> How many declared capabilities have at least one
 * committed trace that exercises them — a statement about the corpus, not about the parser. A
 * capability with no trace may work perfectly; what it lacks is anything that would notice if it
 * stopped. Reading it as "9 of 16 are broken" is the misreading this test is most likely to cause,
 * so the console output says so explicitly on every run.
 *
 * <p>Two things are held here, and they fail in opposite directions on purpose.
 *
 * <p><b>The registry matches the engine.</b> A capability the engine records but the registry does
 * not know is invisible to the coverage map. A capability the registry declares but nothing records
 * is worse: it reports as never-activated forever, and never-activated is the one signal that map
 * exists to produce. Both make the map lie quietly, which is the failure mode the Evidence Rule
 * names.
 *
 * <p><b>Every declared capability is exercised by a committed trace.</b> Measured by running the
 * locator over the trace and reading what actually fired — not by reading the trace's own metadata
 * claim, which would let the corpus grade its own homework. The v3 traces do declare capabilities
 * now, which makes the distinction sharper rather than moot: a declaration is what the capture
 * intended, and this gate reports what the locator actually did. The one v1 trace left declares
 * nothing at all, so a metadata-based gate would still report perfect coverage of nothing.
 *
 * <p>Both use an explicit named accept-list rather than a tolerated count, following
 * {@code LayerDependencyDirectionTest} and {@code check-dependency-advisories.py}. The property
 * that matters is that a <em>stale</em> entry fails too: the day a trace covers one of these, this
 * test goes red until the entry is deleted, so the ratchet tightens by itself instead of waiting for
 * someone to remember.
 *
 * <p><b>{@code @Tag("nightly")}.</b> This is a coverage metric, not a correctness gate — the doc
 * above already says a drop here means "add a trace", not "something broke". Excluded from the
 * default backend suite (backend/pom.xml's {@code excludedGroups}) rather than run on every PR —
 * the one candidate out of four slow corpus-driven tests investigated for this that was actually
 * safe to defer; the other three (trace PII scanning, a silent-data-loss regression sweep, a
 * targeted real-document defect test) stay on every run because deferring any of them risks a real
 * correctness or security regression sitting on main for a day, not just a stale metric.
 *
 * <p>Run by {@code corpus-coverage-nightly.yml} on its own daily schedule, and — added 2026-09-05
 * after PR #930 registered four new capabilities with no committed trace covering any of them, a
 * gap the nightly-only cadence let sit on main for a day before catching (fixed in PR #957) — also
 * on any pull request that touches {@link CapabilityCoverageService}, this class, or the committed
 * trace corpus, so the next capability registered without coverage is caught at review time
 * instead. See that workflow file's own comment for the exact path filter.
 */
@Tag("nightly")
class CapabilityCorpusCoverageTest {

    /**
     * Recorded by the engine, absent from the registry.
     *
     * <p>Empty. Both original entries -- PRINTED_SUMMARY_TOTALS and RIGHT_ALIGNED_AMOUNTS -- were
     * real capabilities and are now registered. The third, UNANCHORED_ROWS_ABANDONED, was not a
     * capability at all and moved to the diagnostics channel.
     *
     * <p>Kept as a field rather than deleted, because an empty accept-list is the assertion: a
     * capability the engine records and the registry has never heard of appears in neither
     * activations nor neverActivated, so the coverage map cannot report the one gap it exists for.
     */
    private static final Map<String, String> RECORDED_BUT_UNDECLARED = Map.of();

    /**
     * Declared in the registry, recorded nowhere.
     *
     * <p>Empty, and that is the point of leaving it here rather than deleting the field: this was
     * the more damaging of the two drifts. A capability nothing records reports as never-activated
     * forever, which is indistinguishable from "no document has needed it" -- and never-activated is
     * the one signal the coverage map exists to produce.
     *
     * <p>Both original entries turned out to be Case A, live capabilities. LEADING_PLUS_CREDIT
     * always recorded and the scan missed it; LEADING_NAME_LINE had always implemented the
     * behaviour and was simply never wired. Neither was obsolete, which is the outcome worth
     * noting: the registry was right and the evidence was missing.
     */
    private static final Map<String, String> DECLARED_BUT_UNRECORDED = Map.of();

    /**
     * Declared capabilities that no committed trace exercises yet.
     *
     * <p>This is the corpus shortfall, named. Ten of nineteen, from three traces --
     * RIGHT_ALIGNED_AMOUNTS came off this list when the two HDFC documents were re-captured with
     * widths intact.
     *
     * <p>It has corrected me twice, in both directions, which is the argument for measuring rather
     * than listing. First run: three capabilities I had listed as uncovered were in fact exercised,
     * because the traces are v1 and declare nothing, so the corpus covered more than it could say
     * for itself. Then, on registering RIGHT_ALIGNED_AMOUNTS, the reverse — I had called it one of
     * the most commonly exercised capabilities and no committed trace activates it at all.
     *
     * <p>Every line removed from here is a layout that stops being a claim and starts being
     * evidence.
     *
     * <p>Deliberately not a count. A count would let one trace be swapped for another with no
     * visible change; a name makes each gap something a person decided to leave open.
     */
    private static final Map<String, String> DECLARED_WITHOUT_A_TRACE = new LinkedHashMap<>();
    static {
        DECLARED_WITHOUT_A_TRACE.put("RUNNING_BALANCE", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("DR_CR_SUFFIX", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("DATE_TIME_COLUMN", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_SIGNAL", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("GRID_METADATA_FALLBACK", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("GRID_METADATA_TRAILING_LABEL", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("FINANCIAL_PRODUCT_CLASSIFICATION", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_SUMMARY_TOTALS", "no trace; newly registered");
        DECLARED_WITHOUT_A_TRACE.put("CHEQUE_PAYABLE_FOOTER_CLOSED",
                "no trace yet -- evidenced from the real axis-credit-card-statement document, which HAS "
                        + "a committed trace, but that trace predates this trigger and was captured before "
                        + "the document's own true-end footer text was known to matter -- re-capturing it "
                        + "is a separate task from adding the trigger. Real-corpus behavior verified "
                        + "directly via CorpusGarbageSweep against the original file instead.");
        DECLARED_WITHOUT_A_TRACE.put("NEUCOINS_FOOTNOTE_CLOSED",
                "no trace yet -- evidenced from a real HDFC \"Tata Neu Plus\" credit-card statement with "
                        + "no committed trace in this corpus. Real-corpus behavior verified directly via "
                        + "CorpusGarbageSweep against the original file instead.");
        DECLARED_WITHOUT_A_TRACE.put("SAVINGS_AND_BENEFITS_SECTION_CLOSED",
                "no trace yet -- evidenced from the real sbi-credit-card-statement document, which HAS a "
                        + "committed trace, but that trace predates this trigger for the same reason as "
                        + "CHEQUE_PAYABLE_FOOTER_CLOSED above. Real-corpus behavior verified directly via "
                        + "CorpusGarbageSweep against the original file instead.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_TRANSACTION_TABLE_DATE_RANGE",
                "no trace -- same scoping gap as GRID_METADATA_FALLBACK above: this fires in "
                        + "TransactionTableDateRangeExtractor, called from PdfPreviewGenerator, never from "
                        + "PdfTableLocator.locateAll -- the one call this test (via TraceValidator) ever "
                        + "makes against a committed trace. Covered instead by "
                        + "KotakCreditCardCategoryAndFooterRegressionTest, run directly against the same "
                        + "committed trace through TransactionTableDateRangeExtractor.extract.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_TITLE_ADJACENT_DATE_RANGE",
                "no trace -- same scoping gap as PRINTED_TRANSACTION_TABLE_DATE_RANGE above: this "
                        + "fires in StatementTitleDateRangeExtractor, called from PdfPreviewGenerator, "
                        + "never from PdfTableLocator.locateAll. The one real evidencing document's "
                        + "committed trace (kotak-savings-ledger-validation) has its date text redacted, "
                        + "so it cannot exercise this capability's recording path either -- see "
                        + "KotakSavingsTitleDateRangeRegressionTest, which asserts against that same trace "
                        + "that the capability correctly does NOT fire on redacted text. Real date-value "
                        + "recovery is covered by StatementTitleDateRangeExtractorTest using the real "
                        + "unredacted date string at the trace's own coordinates.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_PAYMENT_DUE_DATE_GRID",
                "no trace -- same scoping gap as PRINTED_TITLE_ADJACENT_DATE_RANGE above: this fires in "
                        + "PaymentDueDateGridExtractor, called from PdfPreviewGenerator, never from "
                        + "PdfTableLocator.locateAll. Two real evidencing documents have committed traces "
                        + "(axis-credit-card-statement, sbi-credit-card-statement) and both are exercised "
                        + "directly by PaymentDueDateGridRegressionTest -- Axis's trace keeps its dates "
                        + "unredacted, so that test proves the real recovered VALUE end to end, not just "
                        + "that the capability fires. SBI's trace has its date redacted, and that test "
                        + "proves the extractor correctly declines rather than fabricating one.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_CREDIT_LIMIT_GRID",
                "no trace -- same scoping gap as PRINTED_PAYMENT_DUE_DATE_GRID above: this fires in "
                        + "CreditLimitGridExtractor, called from PdfPreviewGenerator, never from "
                        + "PdfTableLocator.locateAll. Three real evidencing documents have committed traces "
                        + "(axis-credit-card-statement, sbi-credit-card-statement, "
                        + "indusland-credit-card-account-number-inheritance) and all three are exercised "
                        + "directly by CreditLimitGridRegressionTest, which proves the real recovered VALUE "
                        + "end to end for each -- none of the three redact their own credit limit value, so "
                        + "unlike PRINTED_PAYMENT_DUE_DATE_GRID's SBI case this needs no separate "
                        + "value-withheld test.");
        DECLARED_WITHOUT_A_TRACE.put("HEADERLESS_LAYOUT_BEFORE_LATER_HEADER",
                "no trace -- the one real evidencing document's committed trace "
                        + "(hsbc-credit-card-yearless-dates) has its closing-balance label redacted "
                        + "(\"NET OUTSTANDING BALANCE\" -> \"XXX OUTSTANDING BALANCE\", same allowlist "
                        + "artifact as PRINTED_PAYMENT_DUE_DATE_GRID's SBI case), which "
                        + "HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED below needs an exact match on to "
                        + "admit the one real transaction below the row-count floor -- so calling "
                        + "PdfTableLocator.locateAll against this trace as-is exercises neither capability. "
                        + "Both are proven directly by HeaderlessBalanceReconciliationTest and "
                        + "HeaderlessLayoutBeforeLaterHeaderTest, end to end through locateAll itself, on "
                        + "fixtures whose row/column GEOMETRY is motivated by the real documents but whose "
                        + "every text value is hand-synthesized per the Synthetic Fixture Policy. Both "
                        + "suites also carry differential guards that were confirmed to FAIL against the "
                        + "pre-fix behaviour, so they pin the mechanism rather than merely covering it.");
        DECLARED_WITHOUT_A_TRACE.put("HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED",
                "no trace -- same reason as HEADERLESS_LAYOUT_BEFORE_LATER_HEADER immediately above; both "
                        + "fire together on the same real document and are blocked by the same trace "
                        + "redaction. See that entry.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_ACCOUNT_NUMBER_GRID",
                "no trace -- same scoping gap as PRINTED_PAYMENT_DUE_DATE_GRID above: this fires in "
                        + "AccountNumberGridExtractor, called from PdfPreviewGenerator, never from "
                        + "PdfTableLocator.locateAll. The one real evidencing document's committed trace "
                        + "(axis-credit-card-statement) redacts its card-number value cells with "
                        + "width=0.0 (breaking the GRID strategy's x-overlap match) and, independently, "
                        + "redacts the cardholder name on the same row into an all-X string "
                        + "indistinguishable in shape from a masked card number (correctly triggering the "
                        + "SAME_ROW strategy's own ambiguity refusal) -- see "
                        + "AccountNumberGridRegressionTest, which asserts against that same trace that the "
                        + "capability correctly does NOT fire on this doubly-redacted text. Real recovery "
                        + "against real geometry is covered by AccountNumberGridExtractorTest using the "
                        + "real coordinates from both of the document's own layouts (grid and same-row), "
                        + "and independently confirmed against the actual un-redacted PDF via "
                        + "scripts/corpus-run.py.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_STATEMENT_PERIOD_GRID",
                "no trace -- same scoping gap as PRINTED_ACCOUNT_NUMBER_GRID above: this fires in "
                        + "StatementPeriodGridExtractor, called from PdfPreviewGenerator, never from "
                        + "PdfTableLocator.locateAll. The one real evidencing document's committed trace "
                        + "(axis-credit-card-statement) keeps its statement-period dates unredacted -- see "
                        + "StatementPeriodGridRegressionTest, which asserts against that same trace that "
                        + "the capability fires and recovers the real range end to end, not just that the "
                        + "label is present.");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_ACCOUNT_NUMBER_ABOVE_TRANSACTIONS",
                "no trace -- same scoping gap as PRINTED_ACCOUNT_NUMBER_GRID above: this fires in "
                        + "AccountNumberTransactionHeaderExtractor, called from PdfPreviewGenerator, never "
                        + "from PdfTableLocator.locateAll. The one real evidencing document's committed "
                        + "trace (icici-credit-card-account-number-above-transactions) redacts the value "
                        + "cell with width=0.0, the same redaction limitation already documented for "
                        + "PRINTED_ACCOUNT_NUMBER_GRID/PRINTED_PAYMENT_DUE_DATE_GRID above, breaking the "
                        + "x-overlap match against the Date header cell -- see "
                        + "AccountNumberTransactionHeaderRegressionTest, which asserts against that trace "
                        + "that the capability correctly does NOT fire on the redacted text. Real recovery "
                        + "against real geometry is covered by AccountNumberTransactionHeaderExtractorTest "
                        + "using the real header/value coordinates, and independently confirmed against the "
                        + "actual un-redacted PDF via scripts/corpus-run.py.");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_TOTALS",
                "no trace yet -- CreditCardSummaryExtractorTest exercises the GRID strategy on "
                        + "synthetic fixtures reproducing real observed shapes (a clean stacked grid, "
                        + "and the row-merge recovery motivated by a real Axis statement), but no "
                        + "committed trace fixture exists for either yet. See the architecture doc's "
                        + "Credit Card Direction Evidence Study addendum for the measured real-corpus "
                        + "fire rate.");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE",
                "no trace yet -- same situation as CREDIT_CARD_SUMMARY_TOTALS above, for the "
                        + "INLINE_LABEL_VALUE strategy motivated by a real AU statement's label-left/value-right "
                        + "layout. Covered by synthetic fixtures in CreditCardSummaryExtractorTest, "
                        + "not yet by a committed real-document trace.");
        DECLARED_WITHOUT_A_TRACE.put("TRANSACTION_TABLE_TOTAL_CLOSED",
                "no trace yet -- motivated by a real Kotak Mahindra Bank credit-card statement's "
                        + "own \"Total Purchase & Other Charges\" column-total row; real-corpus verified "
                        + "directly against the unredacted document (CorpusProbe/PdfPipelineDiagnostic, "
                        + "not just a synthetic reproduction) in the Phase 2A/2C investigation. Capturing "
                        + "a redacted trace from this specific document was attempted and refused by "
                        + "TraceValidator (zero sections survive redaction on this layout) -- an "
                        + "unrelated pre-existing gap in trace capture for this document's shape, not "
                        + "something this change is scoped to fix. Covered by a synthetic fixture in "
                        + "StatementClosingMarkerPdfPreviewGeneratorTest instead, mutation-checked "
                        + "against the pre-fix code.");
        DECLARED_WITHOUT_A_TRACE.put("MITC_SECTION_CLOSED",
                "no trace yet -- motivated by a real ICICI Bank credit-card statement's own all-caps "
                        + "\"MOST IMPORTANT TERMS AND CONDITIONS (MITC)\" section heading; real-corpus "
                        + "verified directly against the unredacted document in the Phase 2A/2C "
                        + "investigation. A redacted trace WAS captured from this document, but the "
                        + "heading sits on page 2 and the captured trace's own text does not reach that "
                        + "far, so it exercises this document's other capabilities without exercising "
                        + "this one -- not committed, since a trace that cannot exercise the capability "
                        + "it would be cited for is not real coverage. Covered by a synthetic fixture in "
                        + "StatementClosingMarkerPdfPreviewGeneratorTest instead, mutation-checked "
                        + "against the pre-fix code.");
        // Four entries below are PR #930's new capabilities. Verified directly (dumped every
        // committed trace's own fired-capability set with PdfTableLocator.locateAll, per this
        // test's own capabilitiesTheCorpusExercises) rather than assumed from the PR description.
        DECLARED_WITHOUT_A_TRACE.put("LOAN_SUMMARY_TABLE_CLOSED",
                "no trace yet -- evidenced from two real HSBC credit-card statements (HSBC CC.pdf, "
                        + "HSBC CC new.pdf), neither of which has a committed trace in this corpus; the "
                        + "two committed HSBC traces (hsbc-credit-card-yearless-dates, "
                        + "hsbc-savings-ledger-validation) are captured from different real documents "
                        + "and confirmed directly not to exercise this capability. Real-corpus behavior "
                        + "verified via the ground-truth gate (scripts/run-corpus-ground-truth.py) "
                        + "against the original files. Covered instead by "
                        + "LoanSummaryTableClosedPdfTableLocatorTest's fully hand-synthesized fixture.");
        DECLARED_WITHOUT_A_TRACE.put("RECONCILED_HEADER_SECTIONS_REMERGED",
                "no trace yet -- evidenced from the real sbi-credit-card-statement and "
                        + "indusland-credit-card-account-number-inheritance documents, which HAVE "
                        + "committed traces, but both traces (captured 2026-08-12 and 2026-09-01) "
                        + "predate this trigger, the same reason already documented for "
                        + "SAVINGS_AND_BENEFITS_SECTION_CLOSED and CHEQUE_PAYABLE_FOOTER_CLOSED above -- "
                        + "confirmed directly, neither trace exercises this capability as committed. "
                        + "Real-corpus behavior verified via the ground-truth gate "
                        + "(scripts/run-corpus-ground-truth.py) against the original files. Covered "
                        + "instead by ReconciledHeaderSectionsRemergedPdfTableLocatorTest's fully "
                        + "hand-synthesized fixture.");
        DECLARED_WITHOUT_A_TRACE.put("EMPTY_SECTION_DROPPED",
                "no trace yet -- evidenced from a real Shivani_HDFC.pdf statement with no committed "
                        + "trace in this corpus. Real-corpus behavior verified via the ground-truth gate "
                        + "(scripts/run-corpus-ground-truth.py) against the original file. Covered "
                        + "instead by EmptySectionDroppedPdfTableLocatorTest's fully hand-synthesized "
                        + "fixture.");
        DECLARED_WITHOUT_A_TRACE.put("INVESTMENT_FRAGMENT_REMERGED",
                "no trace CAN cover it here, for the same scoping reason as "
                        + "PRINTED_TRANSACTION_TABLE_DATE_RANGE above: this fires in "
                        + "PdfPreviewGenerator's orphaned-fragment merge pass, never from "
                        + "PdfTableLocator.locateAll -- the one call this test ever makes against a "
                        + "committed trace. Evidenced from a real Shivani_HDFC.pdf statement with no "
                        + "committed trace in this corpus; real-corpus behavior verified via the "
                        + "ground-truth gate (scripts/run-corpus-ground-truth.py) against the original "
                        + "file. Covered instead by "
                        + "InvestmentFragmentRemergedPdfPreviewGeneratorTest's fully hand-synthesized "
                        + "fixture.");
        // RIGHT_ALIGNED_AMOUNTS was here, with the note "either the three traces genuinely avoid
        // right-aligned amount columns, or the recording sits on a path they do not take. Measure
        // before capturing." It was measured, and the answer was a third thing: the two HDFC
        // traces DO carry right-aligned amount columns and DO take the path, but every committed
        // trace was width-blind -- v1 has no width column, and the v3 captures made before the
        // redactor fix zeroed every width -- so the guard at PdfTableLocator's right-edge redirect
        // (`t.width() > 0`) could never be true on the corpus. The capability was unreachable on
        // the evidence, not unexercised by it. Recapturing both documents with the width-preserving
        // redactor activates it on both.
        DECLARED_WITHOUT_A_TRACE.put("LEADING_PLUS_CREDIT",
                "no trace, and nothing records it -- see DECLARED_BUT_UNRECORDED. A trace cannot "
                        + "cover a capability nothing emits, so this one is blocked on that first.");
        DECLARED_WITHOUT_A_TRACE.put("LEADING_NAME_LINE",
                "no trace, and nothing records it -- same as LEADING_PLUS_CREDIT.");
        DECLARED_WITHOUT_A_TRACE.put("INFERRED_HEADERLESS_LAYOUT",
                "no trace, and none is planned -- the one real document that motivates it is a "
                        + "genuinely headerless statement, so a trace captured from it would need widths "
                        + "recorded from real dates and amounts to reproduce the balance-chain scoring "
                        + "this depends on, which the Synthetic Fixture Policy requires be synthesized, "
                        + "not preserved, for exactly this kind of fixture. Covered instead by "
                        + "HeaderlessLayoutInferenceTest's fully hand-synthesized fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("ILLUSTRATIVE_BLOCK_SUPPRESSED",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT. "
                        + "Covered instead by IllustrativeBlockSuppressionTest's fully hand-synthesized "
                        + "fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("INFERRED_TWO_LINE_DATE_BLOCK",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT. "
                        + "Covered instead by TwoLineDateBlockInferenceTest's fully hand-synthesized "
                        + "fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("PHYSICAL_ROW_DEDUP_EVIDENCE",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT: the "
                        + "one real document known to exercise the headerless path (a real SBI savings "
                        + "statement) contains no repeated physical row for this to remove, so no real "
                        + "trace has ever activated it, and a synthesized trace would need to reproduce "
                        + "the same balance-chain-scoring geometry INFERRED_HEADERLESS_LAYOUT's own "
                        + "entry explains. Covered instead by HeaderlessLayoutInferenceTest's fully "
                        + "hand-synthesized reprinted-row fixture.");
        DECLARED_WITHOUT_A_TRACE.put("CARD_ENDING_DIGITS_IDENTITY",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT: the "
                        + "one real document that motivates it (a real AU Small Finance Bank "
                        + "credit-card statement) would need its actual card-ending sentence "
                        + "preserved for a trace to exercise this, which the Synthetic Fixture Policy "
                        + "requires be synthesized, not preserved. Covered instead by "
                        + "PdfMetadataExtractorTest's fully hand-synthesized fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_IN_SENTENCE",
                "no trace CAN cover it here, for the same reason as "
                        + "ACCOUNT_PRODUCT_BANNER_IDENTITY below: capabilitiesTheCorpusExercises "
                        + "drives PdfTableLocator.locateAll only and never runs "
                        + "PdfMetadataExtractor, so no metadata-extractor capability can be "
                        + "recorded through this harness. Covered instead by "
                        + "PdfMetadataExtractorTest's fully hand-synthesized fixtures, including "
                        + "the year-boundary case.");
        DECLARED_WITHOUT_A_TRACE.put("ACCOUNT_PRODUCT_BANNER_IDENTITY",
                "no trace CAN cover it here, for a different reason than the entries above: "
                        + "capabilitiesTheCorpusExercises drives PdfTableLocator.locateAll only, and "
                        + "never runs PdfMetadataExtractor, so no metadata-extractor capability can "
                        + "ever be recorded through this harness regardless of the corpus. This one "
                        + "is genuinely exercised by a committed trace -- the "
                        + "bob-repeated-account-banner golden snapshot asserts the account number it "
                        + "resolves -- so unlike CARD_ENDING_DIGITS_IDENTITY above, the gap is in "
                        + "this harness's reach, not in the corpus. Widening it to run the metadata "
                        + "extractor would let both entries be deleted.");
        // BLANK_COLUMN_NAME_QUALIFIED and RECOVERED_MISSING_DESCRIPTION_COLUMN are deliberately
        // NOT listed here: the already-committed sbi-credit-card-statement trace turns out to
        // exercise both for real (its own "( ` )" blank-currency cell, and a genuine missing-
        // description recovery elsewhere in its composite structure) -- found by
        // theCorpusShortfallOnlyEverShrinks the moment these were added, exactly the ratchet it
        // exists to enforce. Motivated by a real ICICI savings e-statement either way; also
        // covered by HeaderColumnRecoveryTest's fully hand-synthesized fixtures.
        DECLARED_WITHOUT_A_TRACE.put("RECOVERED_MISSING_SERIAL_NUMBER_COLUMN",
                "no trace, and none is planned -- same reasoning as BLANK_COLUMN_NAME_QUALIFIED, "
                        + "same real document. Covered instead by HeaderColumnRecoveryTest's fully "
                        + "hand-synthesized fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_FROM_TO_FIELDS",
                "no trace yet -- evidenced from real HDFC savings-account statements and a real "
                        + "Sanjay HDFC statement, none of which have a committed trace in this "
                        + "corpus. Real-corpus behavior verified directly via CorpusProbe against "
                        + "the original files instead.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_STATEMENT_FROM_LABEL",
                "no trace yet -- evidenced from real Manas_HDFC, Shivani_HDFC, and Sanjay SBI "
                        + "statements, none of which have a committed trace in this corpus. "
                        + "Real-corpus behavior verified directly via CorpusProbe against the "
                        + "original files instead.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL",
                "no trace yet -- evidenced from a real Central Bank of India statement with no "
                        + "committed trace in this corpus. Real-corpus behavior verified directly "
                        + "via CorpusProbe against the original file instead.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_PROSE",
                "no trace yet -- evidenced from real canara.pdf and ICICI saving.pdf statements, "
                        + "neither of which has a committed trace in this corpus. Real-corpus "
                        + "behavior verified directly via CorpusProbe against the original files "
                        + "instead.");
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_FOR_PERIOD_LABEL",
                "no trace yet -- evidenced from a real PNB ONE savings statement with no "
                        + "committed trace in this corpus. Real-corpus behavior verified directly "
                        + "via CorpusProbe against the original file instead.");
        // PAGE_LEGEND_BLOCK_SUPPRESSED was DECLARED_WITHOUT_A_TRACE here -- the real SBI Credit
        // Card.PDF it was originally evidenced from has no committed trace in this corpus. Entry
        // deleted per this test's own ratchet: kotak-credit-card-category-sections-and-page-footer
        // now exercises it for real too (a second, independently-evidenced real document's own
        // page-1 payment-instructions legend, added to PAGE_LEGEND_BLOCK_START alongside the
        // original SBI phrasing -- see PdfTableLocator.PAGE_LEGEND_BLOCK_START).
        // HEADER_RECONSTRUCTED was DECLARED_WITHOUT_A_TRACE through Phase 2E.2, when
        // reconstructHeader was deliberately scoped to exactly one orphaned single-cell fragment
        // and the real ICICI savings statement's genuine three-cell second tier couldn't be composed
        // without breaking its balance-total check. Phase 2E.5 generalized reconstructHeader to a
        // multi-tier backward-composition walk, and icici-savings-ledger-validation now exercises it
        // for real: composing that three-cell tier recovers the leading transaction correctly, with
        // the balance chain and statement totals both verified. Entry deleted per this test's own
        // ratchet -- see SplitHeaderRunsPdfTableLocatorTest and
        // WrappedHeaderOnAScoringLinePdfTableLocatorTest for the row-count evidence.
    }

    /**
     * {@code ctx.record(...)} — the capability channel, capturing the whole argument expression so
     * every SCREAMING_SNAKE literal inside it is seen, not only a bare string argument.
     *
     * <p>The first version matched {@code .record("NAME")} exactly and missed
     * {@code ctx.record(x.startsWith("+") ? "LEADING_PLUS_CREDIT" : "DR_CR_SUFFIX")}, which made a
     * live capability look unwired and sent me hunting for a fix that was not needed. A scan that
     * under-reports is the worse failure of the two directions this test checks: it can also miss a
     * capability recorded through a ternary and never registered, which is exactly the drift the
     * test exists to catch.
     *
     * <p>Scoped to a {@code ctx.} receiver deliberately. {@code auditService.record(...)} is an
     * unrelated method whose second argument is a SCREAMING_SNAKE audit action.
     */
    private static final Pattern RECORD_CALL = Pattern.compile("\\bctx\\.record\\(([^;]*?)\\)\\s*;");

    /** A capability name inside that call. Four characters minimum, so a stray "PDF" or "OK" in the
     *  same expression is not mistaken for one. */
    private static final Pattern CAPABILITY_LITERAL = Pattern.compile("\"([A-Z][A-Z_]{3,})\"");

    @Test
    void everyCapabilityTheEngineRecordsIsInTheRegistry() {
        Set<String> undeclared = new TreeSet<>(recordedByTheEngine());
        undeclared.removeAll(CapabilityCoverageService.KNOWN_CAPABILITIES);
        undeclared.removeAll(RECORDED_BUT_UNDECLARED.keySet());

        assertThat(undeclared)
                .as("""
                        The engine records a capability the registry has never heard of, so \
                        CapabilityCoverageService cannot report on it -- it will not appear in \
                        activations and will not appear in neverActivated either. Add it to \
                        KNOWN_CAPABILITIES, or decide it is not a capability and stop recording it \
                        through this channel.""")
                .isEmpty();
    }

    @Test
    void everyRegisteredCapabilityIsActuallyRecordedSomewhere() {
        Set<String> phantom = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        phantom.removeAll(recordedByTheEngine());
        phantom.removeAll(DECLARED_BUT_UNRECORDED.keySet());

        assertThat(phantom)
                .as("""
                        The registry declares a capability nothing records. It will report as \
                        never-activated forever, which is indistinguishable from "no document has \
                        needed it" -- and never-activated is the one signal the coverage map exists \
                        to produce. Wire the detection to ctx.record(), or remove the name.""")
                .isEmpty();
    }

    @Test
    void theAcceptListsDoNotOutliveTheProblemsTheyDescribe() {
        Set<String> recorded = recordedByTheEngine();
        List<String> stale = new ArrayList<>();

        RECORDED_BUT_UNDECLARED.keySet().stream()
                .filter(c -> CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c) || !recorded.contains(c))
                .forEach(c -> stale.add("RECORDED_BUT_UNDECLARED: " + c));
        DECLARED_BUT_UNRECORDED.keySet().stream()
                .filter(c -> recorded.contains(c) || !CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c))
                .forEach(c -> stale.add("DECLARED_BUT_UNRECORDED: " + c));

        assertThat(stale)
                .as("""
                        An accept-list entry no longer describes anything real -- the drift it \
                        documented was resolved. Delete it, so the list keeps meaning what it says \
                        and this test gets correspondingly stricter.""")
                .isEmpty();
    }

    @Test
    void everyDeclaredCapabilityIsExercisedByACommittedTrace() {
        Set<String> covered = capabilitiesTheCorpusExercises();

        Set<String> uncovered = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        uncovered.removeAll(covered);
        uncovered.removeAll(DECLARED_WITHOUT_A_TRACE.keySet());

        assertThat(uncovered)
                .as("""
                        A capability is claimed with nothing in the corpus that exercises it, so a \
                        change that breaks it fails on a customer's statement rather than on this \
                        build. Capture a trace, or add it to DECLARED_WITHOUT_A_TRACE with the \
                        reason it is being left uncovered.""")
                .isEmpty();
    }

    /** The ratchet. Every line deleted from the shortfall is a claim that became evidence, and this
     *  is what stops one being added back quietly to make room for another. */
    @Test
    void theCorpusShortfallOnlyEverShrinks() {
        Set<String> covered = capabilitiesTheCorpusExercises();
        List<String> resolved = DECLARED_WITHOUT_A_TRACE.keySet().stream().filter(covered::contains).toList();

        assertThat(resolved)
                .as("""
                        A trace now exercises a capability still listed as uncovered. Delete the \
                        entry -- leaving it there means the shortfall stops describing the corpus \
                        and the number stops being worth reporting.""")
                .isEmpty();

        List<String> unknown = DECLARED_WITHOUT_A_TRACE.keySet().stream()
                .filter(c -> !CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c)).toList();
        assertThat(unknown)
                .as("the shortfall names a capability the registry no longer declares")
                .isEmpty();
    }

    /**
     * Reports coverage rather than asserting it, so the number is visible on every run.
     *
     * <p>Not a soft assertion — the gate above is the assertion. This exists because a shortfall
     * nobody sees is a shortfall nobody closes, and a figure printed on every build is the cheapest
     * form of pressure there is.
     */
    @Test
    void reportsWhereTheCorpusStands() {
        Set<String> declared = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        Set<String> covered = capabilitiesTheCorpusExercises();
        Set<String> declaredAndCovered = new TreeSet<>(declared);
        declaredAndCovered.retainAll(covered);

        // Worded carefully, because this line is what gets quoted. "7 of 16 covered" reads as "9 do
        // not work", and that is not what was measured -- a capability can be correct and simply
        // have no committed trace. What this number says is how much of the engine a parser change
        // cannot silently break, which is a statement about the CORPUS, not about the parser.
        System.out.printf(
                "%n[corpus] %d of %d declared capabilities have at least one regression trace "
                        + "exercising them (%.0f%%), from %d committed trace(s).%n"
                        + "[corpus] This measures evidence, not correctness: an unexercised "
                        + "capability may work perfectly and simply lack a trace.%n",
                declaredAndCovered.size(), declared.size(),
                100.0 * declaredAndCovered.size() / declared.size(),
                PdfTrace.committedTraceNames().size());
        System.out.println("[corpus] exercised by a trace: " + String.join(", ", declaredAndCovered));
        Set<String> remaining = new TreeSet<>(declared);
        remaining.removeAll(declaredAndCovered);
        System.out.println("[corpus] no trace yet:        " + String.join(", ", remaining));

        assertThat(PdfTrace.committedTraceNames())
                .as("the corpus is empty -- every claim about parser coverage is unevidenced")
                .isNotEmpty();
    }

    /**
     * What the corpus actually exercises, by running the locator over each trace and reading what
     * fired.
     *
     * <p>Deliberately not the traces' own {@code capabilities} metadata. A corpus that grades itself
     * on what it claims to cover is not a gate. The two v3 traces now carry capability metadata and
     * the remaining v1 carries none, so a metadata-based measure would grade the corpus on its own
     * claims for two documents and report nothing at all for the third.
     */
    private static Set<String> capabilitiesTheCorpusExercises() {
        Set<String> covered = new LinkedHashSet<>();
        for (String name : PdfTrace.committedTraceNames()) {
            DocumentContext ctx = new DocumentContext("PDF", "CapabilityCorpusCoverageTest");
            new PdfTableLocator().locateAll(PdfTrace.load(name), ctx);
            ctx.capabilities().stream().map(ImportDto.CapabilityActivation::capability).forEach(covered::add);
        }
        return covered;
    }

    /**
     * Every capability name the production sources pass to {@code ctx.record(...)}.
     *
     * <p>Read from source rather than from a constant because there is no enum to read — the names
     * are string literals at their call sites. Scanning is what makes this test able to notice a
     * name that was added without being registered, which is the whole point; the same idiom is
     * already used by {@code MoneyComparisonUsageTest}.
     */
    private static Set<String> recordedByTheEngine() {
        Set<String> recorded = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Matcher call = RECORD_CALL.matcher(Files.readString(p));
                    while (call.find()) {
                        Matcher literal = CAPABILITY_LITERAL.matcher(call.group(1));
                        while (literal.find()) recorded.add(literal.group(1));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(recorded)
                .as("no capability recording found in the sources at all -- the scan is broken, "
                        + "and a broken scan makes every assertion in this class pass vacuously")
                .isNotEmpty();
        return recorded;
    }
}
