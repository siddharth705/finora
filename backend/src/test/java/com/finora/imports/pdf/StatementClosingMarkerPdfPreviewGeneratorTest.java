package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug fix, found against a real Axis Bank Neo Rupay statement: a "**** End of Statement ****"
 * closing marker line, same as a page-number footer, has no date of its own -- without an
 * exclusion for it, PdfTableLocator's ordinary trailing-continuation merge (the same mechanism a
 * genuine wrapped description uses) folded it straight into the last real transaction's
 * description, and the combined row imported as one low-confidence transaction instead of the
 * boilerplate being discarded.
 *
 * <p>Phase 2C widened this to the whole {@link PdfTableLocator#TRAILING_CONTENT_TRIGGERS} family --
 * two more real, bank-specific closing markers (Kotak's own "Total Purchase & Other Charges" table
 * total, ICICI's own all-caps "MOST IMPORTANT TERMS AND CONDITIONS" section heading), sharing the
 * same one-way suppression mechanism the Axis marker established. See
 * docs/architecture/system-design/transaction-boundary-phase2a-investigation.md for the real-corpus
 * evidence behind all three.
 */
class StatementClosingMarkerPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void generate_discardsTheClosingMarkerLine_insteadOfFoldingItIntoTheLastTransaction() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "axis_statement.pdf",
                PdfFixtureBuilder.buildStatementClosingMarkerSample());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(r -> r.description())
                .containsExactly(
                        "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB",
                        "UPI/SAMPLEB ENTERPRISES/PAYCO.S111111@PTY/90000")
                .noneMatch(d -> d.contains("End of Statement"));
    }

    /**
     * Phase 2A/D-29 fix. A row shaped exactly like a real transaction -- its own date, description,
     * and amount -- sitting AFTER the closing marker must not become a staged row just because it
     * happens to parse cleanly. Before this fix, PdfTableLocator never closed the section at the
     * marker; a lookalike row like this one would still be bucketed, surviving only if some
     * unrelated downstream stage happened to reject it. See
     * docs/architecture/system-design/transaction-boundary-phase2a-investigation.md for the
     * real-corpus evidence (a real Axis Bank Minimum-Amount-Due illustration table) this closes.
     */
    @Test
    void generate_doesNotStageARowShapedLikeATransaction_whenItFollowsTheClosingMarker() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "axis_statement.pdf",
                PdfFixtureBuilder.buildStatementClosingMarkerWithTrailingLookalikeSample());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(r -> r.description())
                .containsExactly(
                        "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB",
                        "UPI/SAMPLEB ENTERPRISES/PAYCO.S111111@PTY/90000")
                .noneMatch(d -> d.contains("Illustrative Purchase Example"));
    }

    /**
     * Phase 2C. Same shape as {@link #generate_doesNotStageARowShapedLikeATransaction_whenItFollowsTheClosingMarker}
     * for Kotak's own closing marker ("Total Purchase & Other Charges") instead of Axis's. See
     * docs/architecture/system-design/transaction-boundary-phase2a-investigation.md.
     */
    @Test
    void generate_doesNotStageARowShapedLikeATransaction_whenItFollowsTheTotalRowMarker() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "kotak_statement.pdf",
                PdfFixtureBuilder.buildTransactionTableTotalMarkerWithTrailingLookalikeSample());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(r -> r.description())
                .containsExactly("SAMPLE RETAIL STORE", "UPI-SAMPLE0001234567-SAMPLEVENDOR") // synthetic-ok
                .noneMatch(d -> d.contains("Illustrative Fee Example"));
    }

    /**
     * Phase 2C. Same shape again, for ICICI's own closing marker (the all-caps MITC section
     * heading) instead of Axis's or Kotak's. See
     * docs/architecture/system-design/transaction-boundary-phase2a-investigation.md.
     */
    @Test
    void generate_doesNotStageARowShapedLikeATransaction_whenItFollowsTheMitcHeading() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "icici_statement.pdf",
                PdfFixtureBuilder.buildMitcSectionMarkerWithTrailingLookalikeSample());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(r -> r.description())
                .containsExactly("SAMPLE ONLINE SERVICE IN", "SAMPLE SUBSCRIPTION APP IN")
                .noneMatch(d -> d.contains("Illustrative Interest Example"));
    }

    /**
     * Phase 2C, negative case. {@link PdfTableLocator#MITC_SECTION_MARKER} is deliberately
     * case-sensitive -- real AU and SBI statements both mention the same concept in ordinary
     * mixed-case prose while their own real transactions are still ongoing. A real transaction
     * printed after a mixed-case mention like this one must still be staged; if this ever regresses
     * to a case-insensitive match, this is the test that catches it before it silently truncates a
     * real AU-shaped or SBI-shaped statement.
     *
     * <p>The mixed-case line itself is a dateless row immediately after the first transaction, so
     * it merges into that transaction's description via the ordinary trailing-continuation
     * mechanism -- expected, unrelated to this test's actual point, and not asserted against here.
     * What matters is that the SECOND real transaction, after the mixed-case mention, survives at
     * all: if MITC_SECTION_MARKER regressed to a case-insensitive match, this document would close
     * right there and this row would vanish along with everything real that might follow it.
     */
    @Test
    void generate_stillStagesARealTransaction_afterAMixedCaseMitcMention() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "example_statement.pdf",
                PdfFixtureBuilder.buildMixedCaseMitcMentionDoesNotCloseSample());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows().get(1).description()).isEqualTo("SAMPLE SUBSCRIPTION APP IN");
    }
}
