package com.finora.imports.pdf;

import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfPreviewGenerator.PdfGenerationResult;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Refined test-corpus strategy (docs/engineering/financial-document-intelligence-principles.md,
 * "Test Corpus Strategy" section): most regression fixtures exercise one capability in relative
 * isolation. Real documents rarely activate only one, so these two fixtures deliberately combine
 * several ALREADY-evidenced capabilities (each independently justified by its own real document
 * elsewhere in {@code PdfFixtureBuilder}) in one document, proving they compose correctly rather
 * than only being verified individually. Nothing here is speculative -- no new header aliases, no
 * new layout shapes, no capability without a prior real-document justification.
 */
class CapabilityCompositionPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator()));
    }

    private List<String> activatedCapabilities(byte[] pdfBytes, String filename) throws Exception {
        PdfGenerationResult result = realGenerator().generateSectionsWithContext(UUID.randomUUID(), filename, pdfBytes);
        return result.documentContext().capabilities().stream().map(a -> a.capability()).toList();
    }

    @Test
    void runningBalanceWrappedDescriptionRepeatedHeader_allThreeCapabilitiesFireTogether() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildRunningBalanceWrappedDescriptionRepeatedHeaderSample(), "composite_a.pdf");

        assertThat(activated).contains("RUNNING_BALANCE", "WRAPPED_DESCRIPTION", "REPEATED_HEADER");
    }

    @Test
    void runningBalanceWrappedDescriptionRepeatedHeader_stillStagesEveryRowCorrectly() throws Exception {
        // Composability isn't just "the right capability names got recorded" -- the actual staged
        // data must still be correct when multiple capabilities interact on the same document.
        var response = realGenerator().generate(UUID.randomUUID(), "composite_a.pdf",
                PdfFixtureBuilder.buildRunningBalanceWrappedDescriptionRepeatedHeaderSample());

        // 3 real transactions -- the repeated header on page 2 must not be staged as a 4th row.
        assertThat(response.rows()).hasSize(3);
        assertThat(response.rows()).extracting(r -> r.description())
                .containsExactly("Salary Credit", "UPI-Amazon India Purchase (Ref# ORDER-8817234451)", "Grocery Store");
        assertThat(response.rows()).extracting(r -> r.balanceAfter().stripTrailingZeros().toPlainString())
                .contains("50000", "48800", "46800");
    }

    @Test
    void offsetAnchorsGridMetadataPageBoundary_allThreeCapabilitiesFireTogether() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildOffsetAnchorsGridMetadataPageBoundarySample(), "composite_b.pdf");

        assertThat(activated).contains("OFFSET_COLUMN_ANCHORS", "GRID_METADATA_FALLBACK", "PAGE_BOUNDARY_ISOLATION");
    }

    @Test
    void offsetAnchorsGridMetadataPageBoundary_stillExtractsCorrectData() throws Exception {
        var response = realGenerator().generate(UUID.randomUUID(), "composite_b.pdf",
                PdfFixtureBuilder.buildOffsetAnchorsGridMetadataPageBoundarySample());

        // The page-number footer line must not pollute or drop a real transaction row.
        assertThat(response.rows()).hasSize(3);
        // The offset-anchor recovery must still correctly locate every row's date, despite the
        // header/data x-position mismatch this fixture exists to exercise.
        assertThat(response.rows()).extracting(r -> r.date().toString())
                .containsExactly("2026-06-24", "2026-07-02", "2026-07-10");
        // The grid-metadata fallback must still find the due date from the payment-summary block.
        assertThat(response.detectedAccount().paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 9));
    }
}
