package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A payment-summary block laying its Due Date field out as a grid (a trailing "...DUE DATE"
 * label line, an unrelated intervening line, then a value line whose last date-shaped token is
 * the actual value) rather than a single "Label: Value" line. Modeled on a real HDFC "Tata Neu
 * Plus" statement, but the pattern isn't specific to it -- this was previously verified only
 * against that one real file by hand (see the Capability Registry in
 * docs/engineering/financial-document-intelligence-principles.md); this is its first synthetic
 * regression test.
 */
class GridMetadataFallbackPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildGridMetadataFallbackSample();
        return realGenerator().generate(UUID.randomUUID(), "grid_metadata_statement.pdf", bytes);
    }

    @Test
    void generate_extractsPaymentDueDate_fromTheGridLayout_notASingleLabelValueLine() throws Exception {
        StagingResponse response = generate();

        assertThat(response.detectedAccount().paymentDueDate()).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    void generate_isNotMisledByTheIrrelevantInterveningLine() throws Exception {
        // "(Including Cash)" sits between the label and the value line -- the bounded-window
        // search must see past it, not stop there or grab a date-shaped token from it (it has
        // none, but a looser implementation stopping at the first non-matching line would still
        // fail this by never reaching the real value line at all).
        StagingResponse response = generate();

        assertThat(response.detectedAccount().paymentDueDate()).isNotNull();
    }

    @Test
    void generate_stillDetectsCreditCardTypeAndTheTransactionTable() throws Exception {
        StagingResponse response = generate();

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).type()).isEqualTo("EXPENSE");
        assertThat(response.rows().get(0).amount()).isEqualByComparingTo("500.00");
    }
}
