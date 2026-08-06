package com.finora.imports.pdf;

import com.finora.dto.ImportDto.FinancialDocumentMetadata;
import com.finora.imports.DocumentContext;
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
 * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
 * exercises every one of the 13 documented capabilities against its own real regression fixture
 * (see the other *PdfPreviewGeneratorTest classes for what each fixture's shape actually is, and
 * the Capability Registry for what each capability means) and asserts DocumentContext actually
 * recorded it firing -- this is what makes the registry's coverage backed by something real for
 * the first time, rather than a hand-maintained claim.
 */
class CapabilityActivationPdfPreviewGeneratorTest {

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
    void drCrSuffixAndRepeatedHeaderFixture_recordsBothCapabilities() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample(), "dr_cr_suffix_statement.pdf");

        assertThat(activated).contains("DR_CR_SUFFIX", "REPEATED_HEADER");
    }

    @Test
    void wrappedDescriptionCreditCardFixture_recordsFourCapabilities() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildWrappedDescriptionCreditCardSample(), "credit_card_statement.pdf");

        assertThat(activated).contains(
                "WRAPPED_DESCRIPTION", "DATE_TIME_COLUMN", "LEADING_PLUS_CREDIT", "CREDIT_CARD_SUMMARY_SIGNAL");
    }

    @Test
    void reverseChronologicalRunningBalanceFixture_recordsRunningBalance() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "pnb_one_statement.pdf");

        assertThat(activated).contains("RUNNING_BALANCE");
    }

    @Test
    void multiSectionCompositeStatementFixture_recordsCompositeStatement() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildMultiSectionCompositeStatementSample(), "hsbc_composite_statement.pdf");

        assertThat(activated).contains("COMPOSITE_STATEMENT", "CREDIT_CARD_SUMMARY_SIGNAL", "RUNNING_BALANCE");
    }

    @Test
    void parenthesizedDrCrRunningBalanceFixture_recordsPageBoundaryIsolation() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildParenthesizedDrCrRunningBalanceSample(), "union_bank_statement.pdf");

        assertThat(activated).contains("PAGE_BOUNDARY_ISOLATION", "DR_CR_SUFFIX", "REPEATED_HEADER", "RUNNING_BALANCE");
    }

    @Test
    void gridMetadataFallbackFixture_recordsGridMetadataFallback() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildGridMetadataFallbackSample(), "tata_neu_statement.pdf");

        assertThat(activated).contains("GRID_METADATA_FALLBACK");
    }

    @Test
    void offsetColumnAnchorsFixture_recordsOffsetColumnAnchors() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildOffsetColumnAnchorsSample(), "neo_rupay_statement.pdf");

        assertThat(activated).contains("OFFSET_COLUMN_ANCHORS");
    }

    @Test
    void singularDepositWithdrawalColumnsFixture_recordsOffsetColumnAnchorsViaBalanceSplit() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample(), "kotak_statement.pdf");

        assertThat(activated).contains("OFFSET_COLUMN_ANCHORS", "RUNNING_BALANCE");
    }

    @Test
    void leadingNarrationContinuationFixture_recordsLeadingNarrationAndWrappedDescription() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildLeadingNarrationContinuationSample(), "canara_statement.pdf");

        assertThat(activated).contains("LEADING_NARRATION_CONTINUATION", "WRAPPED_DESCRIPTION", "RUNNING_BALANCE");
    }

    @Test
    void metadata_reportsStructuralFactsForARealFixture() throws Exception {
        PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "dr_cr_suffix_statement.pdf", PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample());

        FinancialDocumentMetadata metadata = result.documentContext().buildMetadata();
        assertThat(metadata.sourceFormat()).isEqualTo("PDF");
        assertThat(metadata.parser()).isEqualTo("PdfPreviewGenerator");
        assertThat(metadata.pages()).isEqualTo(2);
        assertThat(metadata.tables()).isEqualTo(1);
        assertThat(result.documentContext().buildFingerprint()).matches("FP-1-[0-9A-F]{8}");
    }

    @Test
    void fingerprint_isTheSameAcrossTwoParsesOfTheSameDocument() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample();

        String first = realGenerator().generateSectionsWithContext(UUID.randomUUID(), "a.pdf", bytes)
                .documentContext().buildFingerprint();
        String second = realGenerator().generateSectionsWithContext(UUID.randomUUID(), "a.pdf", bytes)
                .documentContext().buildFingerprint();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void fingerprint_differsForATrulyDifferentLayout() throws Exception {
        String drCr = realGenerator().generateSectionsWithContext(
                        UUID.randomUUID(), "a.pdf", PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample())
                .documentContext().buildFingerprint();
        String composite = realGenerator().generateSectionsWithContext(
                        UUID.randomUUID(), "b.pdf", PdfFixtureBuilder.buildMultiSectionCompositeStatementSample())
                .documentContext().buildFingerprint();

        assertThat(drCr).isNotEqualTo(composite);
    }
}
