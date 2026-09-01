package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-002 Fix 2: {@code PdfTableLocator.looksLikeHeaderRow} rejects a coalesced header cell longer
 * than {@code MAX_HEADER_CELL_WORDS} (12) words, because a paragraph of prose -- a fee schedule, a
 * T&amp;C clause, an MITC disclosure -- is not a column name no matter how many of its ordinary
 * English words happen to also be {@code HEADER_HINTS}.
 *
 * <p>This is the root-cause half of P-002. Fix 1 ({@link com.finora.imports.MultiSectionZeroExtractionTest})
 * stops the user-visible harm by rejecting a document that stages nothing anywhere, however many
 * sections it was cut into. This fix stops the parser from cutting the document into those extra
 * sections in the first place. Both are needed and neither replaces the other -- Fix 1 remains the
 * safety net for whatever prose Fix 2's word cap does not catch (see AU and SBI below, whose extra
 * sections are genuine tables, not prose, and are correctly left alone).
 *
 * <p>Every number in this file was measured directly against the committed trace corpus with
 * {@link PdfTableLocator#locateAll} and the real {@link PdfPreviewGenerator}, not copied from the
 * investigation document.
 */
class HeaderProseRejectionTest {

    private final UUID userId = UUID.randomUUID();

    // ================================================================================
    // 1. The required before/after table, section counts and staged transactions together.
    // ================================================================================

    private record Expectation(int sections, int staged) {}

    private static final Map<String, Expectation> EXPECTED = new LinkedHashMap<>() {{
        // PdfTableLocator.locateAll's own section count goes to ZERO -- every one of Kotak's eight
        // sections was a prose fragment. PdfPreviewGenerator's fallback for "no located sections"
        // folds the whole document into one all-unparseable section, which is a SEPARATE,
        // downstream fact asserted by MultiSectionZeroExtractionTest rather than here.
        put("kotak-credit-card-ledger-validation", new Expectation(0, 0));
        // 4 before PdfTableLocator.looksLikePaymentSummaryPanel, 3 after: this trace's own
        // "Available Credit Limit / Payment Due Date / Available Cash Limit" section was the same
        // misdetected payment-summary panel found on the real Axis and HDFC credit statements,
        // not prose -- a separate fix from Fix 2's word cap, landing in the same section count.
        put("sbi-credit-card-statement", new Expectation(3, 0));
        put("icici-credit-card-statement", new Expectation(1, 3));
        // Unchanged: AU's extra sections are a genuine (if unparseable) transaction block and a
        // genuine interest-computation schedule -- not prose, so the word cap must not touch them.
        put("au-credit-card-statement", new Expectation(3, 0));
        // 2 before looksLikePaymentSummaryPanel, 1 after: Axis's fine print was already handled by
        // MAX_HEADER_ROW_CELLS/the density guard before this fix (still is), but its OTHER section
        // was the real document's own "PAYMENT SUMMARY" panel, misread as a header -- now
        // suppressed the same way. Its 108 genuine transactions must not move by even one row.
        put("axis-credit-card-statement", new Expectation(1, 108));
    }};

    /** The control document. MUST be byte-identical -- asserted at full response detail, not just
     *  section/row counts, because a fix that happened to preserve the numbers while silently
     *  changing which rows landed where would still be wrong. */
    private static final String CONTROL = "hdfc-composite-deposit-schedules";

    @Test
    void everyAffectedTrace_matchesItsMeasuredBeforeAfterSectionAndStagedCounts() {
        for (Map.Entry<String, Expectation> e : EXPECTED.entrySet()) {
            String trace = e.getKey();
            PdfTableLocator.LocatedDocument located = new PdfTableLocator().locateAll(PdfTrace.load(trace));
            assertThat(located.sections())
                    .as("PdfTableLocator.locateAll section count for %s", trace)
                    .hasSize(e.getValue().sections());

            List<StagedAccountSection> generated = generate(trace);
            int staged = generated.stream().mapToInt(s -> s.rows().size()).sum();
            assertThat(staged)
                    .as("total staged transactions across the whole document for %s", trace)
                    .isEqualTo(e.getValue().staged());
        }
    }

    /**
     * Risk 5 from the investigation, made explicit rather than left as an implication of a section
     * count changing. Kotak stages zero transactions before Fix 2 (as eight phantom accounts) and
     * zero after (correctly rejected) -- the number "zero" does not move, but WHAT HAPPENS to that
     * zero does: a confusing eight-account review screen becomes a single, honest rejection. This
     * is the bug becoming visible, not a regression, and the rejection itself (not just the section
     * count) is what proves the fix landed.
     */
    @Test
    void kotak_zeroStagedTransactions_isRejectedRatherThanOfferedAsPhantomAccounts() throws Exception {
        PdfPreviewGenerator generator = generatorFor("kotak-credit-card-ledger-validation");
        List<StagedAccountSection> sections = generator.generateSections(
                userId, "kotak.pdf", new byte[]{1}, null);

        // What PdfPreviewGenerator alone produces: one section (its own fallback for zero located
        // sections), unparseable, zero transactions. Whether that is REJECTED is ImportService's
        // and ExtractionCheck's job, already covered by MultiSectionZeroExtractionTest -- restated
        // here only as the shape this fix hands downstream, not duplicated as a rejection test.
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).rows()).isEmpty();
    }

    // ================================================================================
    // 2. Risk 1 -- WRAPPED_HEADER (P-001) interaction.
    // ================================================================================

    /**
     * The two traces the investigation names as the wrapped-header corpus. A word cap applied to a
     * MERGED two-line header could plausibly double-count words and reject a genuine multi-band
     * header -- explicitly re-verified here rather than assumed safe from the corpus-wide sweep
     * alone, because these are the two documents that exercise {@code wrappedHeaderAt}'s merge path.
     */
    @Test
    void wrappedHeaderTraces_stillMergeAndStageExactlyAsBefore() {
        PdfTableLocator.LocatedDocument centralBank = new PdfTableLocator()
                .locateAll(PdfTrace.load("central-bank-savings-ledger-validation"));
        assertThat(centralBank.sections()).as("central-bank: still one section post-merge").hasSize(1);
        assertThat(centralBank.sections().get(0).rows()).as("central-bank: wrapped header still yields 223 located rows")
                .hasSize(223);

        PdfTableLocator.LocatedDocument hdfcTxnDate = new PdfTableLocator()
                .locateAll(PdfTrace.load("hdfc-txn-date-narration-header"));
        assertThat(hdfcTxnDate.sections()).as("hdfc-txn-date-narration-header: still one section").hasSize(1);
        assertThat(hdfcTxnDate.sections().get(0).rows()).as("still 5 rows located").hasSize(5);

        // And staged all the way through the real generator, not just located.
        assertThat(generate("central-bank-savings-ledger-validation").stream()
                .mapToInt(s -> s.rows().size()).sum()).isEqualTo(222);
        assertThat(generate("hdfc-txn-date-narration-header").stream()
                .mapToInt(s -> s.rows().size()).sum()).isEqualTo(4);
    }

    // ================================================================================
    // 3. Risk 2 -- backward pollution: a rejected header must not become a data row.
    // ================================================================================

    /**
     * SBI's fifth section (before Fix 2) is exactly the shape the investigation calls out: a
     * 221-character/31-word EMI-legal-text paragraph. Post-Fix-2 it stops scoring as a header, and
     * the risk is that its two lines get bucketed as ordinary DATA ROWS of the section still open
     * when the parser reaches them (SBI's fourth section) rather than falling to auxiliary text.
     * Asserted at CONTENT equality, not row count: the fourth section's own rows, before and after,
     * must be the identical two rows they always were.
     */
    @Test
    void sbi_rejectedFifthSectionProse_doesNotPolluteThePrecedingSectionsRows() {
        PdfTableLocator.LocatedDocument after = new PdfTableLocator()
                .locateAll(PdfTrace.load("sbi-credit-card-statement"));
        // 4 before PdfTableLocator.looksLikePaymentSummaryPanel, 3 after: the second of the
        // original four sections was itself a misdetected payment-summary panel (see EXPECTED's
        // own comment above), so the section this test cares about -- the one the rejected fifth
        // section's prose sits after -- is now index 2, not 3.
        assertThat(after.sections()).hasSize(3);

        // Row content here changed under Phase 2E.5's HSBC row-formation fix (groupIntoRows' now
        // chain-based clustering, header-reconstruction-design.md §9.4): this section's own header
        // line ("Date" | "Amount ( ` )" | its own "( ` )" sub-label) sits 2.36pt below a "for
        // Statement Period: ... to ..." caption -- close enough that chain-based clustering
        // correctly folds the caption onto the header's own physical row, where the pre-fix
        // anchor-based comparison kept it separate. buildHeaderColumns' containsEmbeddedDateRange
        // guard (added alongside the row-formation fix specifically to stop a caption like this
        // becoming a phantom, permanently-empty header column -- see that method's own comment)
        // does NOT catch it here: this trace's own dates are redacted to a non-parseable
        // placeholder shape ("99 Xxx 99"), the same already-documented limitation
        // header-reconstruction-design.md §9.2 found blocking reconstructHeader's OWN validation
        // on this exact trace. containsEmbeddedDateRange is verified against a real, parseable date
        // shape instead, in OrphanedHeaderRowCaptionTest. Net effect on this ONE redacted trace: the
        // caption still becomes a header column, but (as before either fix) no row's data ever
        // buckets near its anchor, so it never appears as a key -- an existing, unrelated
        // characteristic of this document's header shape, not something either fix changed.
        // "( ` )" and "Amount" no longer coalesce as of the fix verified against a real (unredacted)
        // SBI credit-card statement: that literal column is this bank's Credit/Debit marker, not a
        // decorative currency suffix -- coalesced away, a real row's marker value had nowhere to
        // bucket into and glued onto the amount instead ("25.00 D"), which fails
        // CsvParser.parseNumeric outright. See PdfTableLocator.RUPEE_ARTIFACT_MARKER_COLUMN's own
        // doc comment. This redacted trace's own placeholder marker value ("X") now lands in that
        // column on its own, same as the amount and the marker are two real, separate cells here too.
        PdfTableLocator.LocatedSection lastSection = after.sections().get(2);
        assertThat(lastSection.rows()).hasSize(2);
        assertThat(lastSection.rows().get(0))
                .as("first row of the section the rejected prose sits after -- must be the genuine "
                        + "transaction-block row, not a bucketed fragment of the rejected paragraph")
                .containsEntry("Date", "99 Xxx 99 UPI-XXXXXX")
                .containsEntry("Amount", "25.00")
                .containsEntry("( ` )", "X");
        assertThat(lastSection.rows().get(1).values())
                .as("second row: still the real (unparsed) transaction dump, not the rejected prose")
                .anySatisfy(v -> assertThat(v).contains("UPI-XXXXXX"));
        // The rejected paragraph's own text is not silently discarded -- "never lose information" --
        // it grows the section's AUXILIARY text instead of becoming a row.
        assertThat(lastSection.auxiliaryText().size())
                .as("the rejected section's own two lines (plus its header line) land here instead")
                .isGreaterThan(76);
    }

    /**
     * The same check on ICICI, whose rejected section (108 chars / 19 words, per the investigation)
     * sits BEFORE the one surviving genuine section rather than after a chain of others. The
     * surviving section's first and last row are asserted verbatim.
     */
    @Test
    void icici_rejectedSectionProse_doesNotPolluteTheSurvivingSectionsRows() {
        PdfTableLocator.LocatedDocument after = new PdfTableLocator()
                .locateAll(PdfTrace.load("icici-credit-card-statement"));
        assertThat(after.sections()).hasSize(1);

        PdfTableLocator.LocatedSection only = after.sections().get(0);
        assertThat(only.rows()).hasSize(6);
        assertThat(only.rows().get(0))
                .as("the genuine header's own first row, unpolluted by the two rejected sections ahead of it")
                .containsEntry("Xxxxxx", "Xxxxxx");
        assertThat(only.auxiliaryText().size())
                .as("both rejected leading sections' content (15 + 2 rows' worth) folds into this "
                        + "section's auxiliary text instead of becoming rows")
                .isGreaterThan(204);
    }

    /** AU is unaffected end to end -- included as the negative control for the pollution checks
     *  above: a document whose extra sections are genuine (not prose) must be BYTE IDENTICAL, not
     *  merely "no pollution detected". */
    @Test
    void au_genuineExtraSections_areCompletelyUnaffected() {
        PdfTableLocator.LocatedDocument after = new PdfTableLocator()
                .locateAll(PdfTrace.load("au-credit-card-statement"));
        assertThat(after.sections()).hasSize(3);
        assertThat(after.sections().get(0).rows()).hasSize(2);
        assertThat(after.sections().get(1).rows()).hasSize(2);
        assertThat(after.sections().get(2).rows()).hasSize(2);
        assertThat(after.sections().get(0).rows().get(0))
                .containsEntry("Date", "99- Xxx");
        assertThat(after.sections().get(2).rows().get(0))
                .containsEntry("Date", "99- Xxx");
    }

    // ================================================================================
    // 4. The control document -- byte-identical.
    // ================================================================================

    @Test
    void hdfcComposite_controlDocument_isByteIdentical() {
        PdfTableLocator.LocatedDocument located = new PdfTableLocator().locateAll(PdfTrace.load(CONTROL));
        assertThat(located.sections()).as("still four located sections").hasSize(4);
        assertThat(located.sections().stream().mapToInt(s -> s.rows().size()).sum())
                .as("still 102 located rows across all four sections")
                .isEqualTo(102);

        List<StagedAccountSection> generated = generate(CONTROL);
        assertThat(generated).hasSize(4);
        int staged = generated.stream().mapToInt(s -> s.rows().size()).sum();
        assertThat(staged).as("still 75 staged transactions").isEqualTo(75);
    }

    // ================================================================================
    // 5. Corpus sweep -- every committed trace lands in an expected bucket.
    // ================================================================================

    /** The five explicitly measured traces, the control, and every other trace in the corpus, which
     *  must be provably UNCHANGED rather than merely unexamined. A newly captured trace that lands
     *  in none of these buckets fails loudly here instead of silently passing uncovered. */
    private static final Map<String, Integer> UNCHANGED_SECTION_COUNTS = new LinkedHashMap<>() {{
        put("bob-repeated-account-banner", 1);
        put("bob-savings-ledger-validation", 1);
        put("canara-savings-ledger-validation", 1);
        put("central-bank-savings-ledger-validation", 1);
        // 2 before PdfTableLocator.looksLikePaymentSummaryPanel, 1 after: this trace's first
        // section was the same misdetected payment-summary panel as the real Axis/HDFC/SBI
        // documents, not something Fix 2's word cap ever touched -- moved out of "unchanged"
        // rather than dropped, since it is still exactly-one-value-per-key stable going forward.
        put("hdfc-credit-card-ledger-validation", 1);
        put("hdfc-savings-ledger-validation", 1);
        put("hdfc-savings-multi-page-ledger", 1);
        put("hdfc-savings-single-page-ledger", 1);
        put("hdfc-txn-date-narration-header", 1);
        put("hsbc-savings-ledger-validation", 1);
        put("icici-savings-ledger-validation", 1);
        put("kotak-credit-card-category-sections-and-page-footer", 1);
        put("kotak-savings-ledger-validation", 1);
        put("pnb-savings-ledger-validation", 1);
        put("union-bank-savings-ledger-validation", 1);
        put("cbi-account-discrepancy-disclaimer-trailer", 1);
        put("pnb-one-account-discrepancy-disclaimer-trailer", 1);
        // Captured for the 2026-08-29 lineOf X-ordering fix (docs/superpowers/specs/
        // 2026-08-29-lineof-x-ordering-fix-design.md) -- its own content (transaction narration
        // join order) is verified by TraceFixtureRegressionTest, not this class; listed here only
        // so this inventory sweep accounts for it at all.
        put("bob-transaction-row-x-ordering", 1);
        // Captured for PdfTableLocator.resolveYearlessDate (docs/superpowers/plans/
        // 2026-09-01-hsbc-yearless-date-resolution.md) -- its own content is verified by
        // YearlessDateResolutionTest and HsbcCreditCardYearlessDateRegressionTest, not this
        // class; listed here only so this inventory sweep accounts for it at all.
        put("hsbc-credit-card-yearless-dates", 1);
    }};

    @Test
    void everyOtherCorpusTrace_hasAnUnchangedSectionCount() {
        for (Map.Entry<String, Integer> e : UNCHANGED_SECTION_COUNTS.entrySet()) {
            PdfTableLocator.LocatedDocument located = new PdfTableLocator().locateAll(PdfTrace.load(e.getKey()));
            assertThat(located.sections())
                    .as("section count for %s, which this fix must not touch", e.getKey())
                    .hasSize(e.getValue());
        }
    }

    @Test
    void everyCommittedTrace_isCoveredByExactlyOneOfTheListsAbove() {
        assertThat(PdfTrace.committedTraceNames()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.of(EXPECTED.keySet().stream(), java.util.stream.Stream.of(CONTROL),
                                UNCHANGED_SECTION_COUNTS.keySet().stream())
                        .flatMap(s -> s).toList());
    }

    // ---------------------------------------------------------------- harness

    private record TraceAcquirer(List<PositionedText> runs) implements DocumentTextAcquirer {
        TraceAcquirer(String trace) { this(PdfTrace.load(trace)); }
        @Override public AcquiredDocument acquire(byte[] fileBytes, String password) { return AcquiredDocument.of(runs); }
        @Override public boolean supports(byte[] fileBytes) { return true; }
    }

    private List<StagedAccountSection> generate(String trace) {
        try {
            return generatorFor(trace).generateSections(userId, trace + ".pdf", new byte[]{1}, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PdfPreviewGenerator generatorFor(String trace) {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        return new PdfPreviewGenerator(new TraceAcquirer(trace), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
    }
}
