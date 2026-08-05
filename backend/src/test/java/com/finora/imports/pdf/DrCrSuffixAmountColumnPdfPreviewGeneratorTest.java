package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.CsvParser;
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
 * A credit-card statement layout with a single Amount column carrying a trailing Dr/Cr suffix
 * (no separate Type/Credit column) and a header row repeated verbatim on a second page -- modeled
 * on Axis Bank's "Neo Rupay" statement, but the pattern isn't specific to that bank; any
 * card issuer could ship this same column shape.
 */
class DrCrSuffixAmountColumnPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.BalanceChainValidator());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample();
        return realGenerator().generate(UUID.randomUUID(), "dr_cr_suffix_statement.pdf", bytes);
    }

    @Test
    void generate_extractsRowsFromBothPages_withoutStagingTheRepeatedHeaderAsARow() throws Exception {
        StagingResponse response = generate();

        // 3 rows on page 1 + 2 rows on page 2 -- the repeated header row on page 2 must be
        // recognized and skipped, not staged as a garbage 6th row.
        assertThat(response.rows()).hasSize(5);
    }

    @Test
    void generate_classifiesDrAndCrSuffixCorrectly_withNoSeparateTypeColumn() throws Exception {
        StagingResponse response = generate();

        var debit = response.rows().stream().filter(r -> r.description().contains("TOBOX VENTURES")).findFirst().orElseThrow();
        assertThat(debit.type()).isEqualTo("EXPENSE");
        assertThat(debit.amount()).isEqualByComparingTo("37.94");

        var credit = response.rows().stream().filter(r -> r.description().contains("BBPS PAYMENT")).findFirst().orElseThrow();
        assertThat(credit.type()).isEqualTo("INCOME");
        assertThat(credit.amount()).isEqualByComparingTo("10081.99");
    }

    @Test
    void generate_detectsCreditCardAccountType_fromThePaymentSummaryText_notATableColumn() throws Exception {
        StagingResponse response = generate();

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
    }

    @Test
    void detectSignFromRawAmount_recognizesTrailingDrAndCr() {
        assertThat(CsvParser.detectSignFromRawAmount("37.94 Dr")).isFalse();
        assertThat(CsvParser.detectSignFromRawAmount("10,081.99 Cr")).isTrue();
        assertThat(CsvParser.detectSignFromRawAmount("100.00")).isNull();
    }
}
