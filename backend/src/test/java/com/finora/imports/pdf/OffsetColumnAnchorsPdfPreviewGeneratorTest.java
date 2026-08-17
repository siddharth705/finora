package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
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
 * A layout where the header row's column labels don't line up with where each column's data
 * actually starts -- see {@code PdfFixtureBuilder.buildOffsetColumnAnchorsSample}'s own doc
 * comment for the real Axis Bank statement this is modeled on. Covers three related bugs found
 * against that real file, all in {@code PdfTableLocator.bucketRow}: a description column's data
 * being swallowed into the DATE cell, a short amount value being swallowed into a short
 * merchant-category cell, and a fee line whose label and amount were extracted as a single
 * combined text run. Also covers a fine-print paragraph that used to be misread as a second
 * table's header, wrongly splitting the document into two account sections.
 */
class OffsetColumnAnchorsPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private List<StagedAccountSection> generateSections() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildOffsetColumnAnchorsSample();
        return realGenerator().generateSections(UUID.randomUUID(), "offset_column_anchors.pdf", bytes);
    }

    @Test
    void generateSections_doesNotSplitIntoASecondSection_dueToFinePrintContainingDateAndAmount() throws Exception {
        List<StagedAccountSection> sections = generateSections();
        assertThat(sections).hasSize(1);
    }

    @Test
    void generateSections_extractsAllThreeTransactions_despiteTheHeaderLabelsNotAligningWithColumnData() throws Exception {
        StagedAccountSection section = generateSections().get(0);
        assertThat(section.rows()).hasSize(3);
    }

    @Test
    void generateSections_doesNotSwallowTheDescriptionIntoTheDateCell() throws Exception {
        StagedAccountSection section = generateSections().get(0);

        var row = section.rows().stream()
                .filter(r -> r.description().contains("SAMPLE VENDOR")).findFirst().orElseThrow();
        assertThat(row.date()).isEqualTo(java.time.LocalDate.of(2026, 6, 24));
        assertThat(row.type()).isEqualTo("EXPENSE");
        assertThat(row.amount()).isEqualByComparingTo("37.94");
    }

    @Test
    void generateSections_doesNotSwallowAShortAmount_intoAShortMerchantCategoryCell() throws Exception {
        StagedAccountSection section = generateSections().get(0);

        var row = section.rows().stream()
                .filter(r -> r.description().contains("SAMPLE HEALTH CENTRE")).findFirst().orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void generateSections_splitsATrailingAmount_offOfAFeeLineRenderedAsOneCombinedTextRun() throws Exception {
        StagedAccountSection section = generateSections().get(0);

        var row = section.rows().stream()
                .filter(r -> r.description().contains("FUEL SURCHARGE")).findFirst().orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo("10.00");
        assertThat(row.type()).isEqualTo("EXPENSE");
    }
}
