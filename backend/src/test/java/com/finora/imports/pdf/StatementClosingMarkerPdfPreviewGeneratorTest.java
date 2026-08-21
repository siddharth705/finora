package com.finora.imports.pdf;

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
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
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
}
