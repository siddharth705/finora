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
 * A savings-account layout combining a parenthesized Dr/Cr amount suffix ("50000.00(Cr)") with a
 * running balance column, split across two pages with a page-number footer and a repeated title
 * banner -- both of which must be recognized as noise rather than merged into a real transaction.
 * Modeled on a real Union Bank of India statement, but the pattern isn't specific to it.
 */
class ParenthesizedDrCrRunningBalancePdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator()));
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildParenthesizedDrCrRunningBalanceSample();
        return realGenerator().generate(UUID.randomUUID(), "parenthesized_dr_cr_statement.pdf", bytes);
    }

    @Test
    void generate_extractsAllThreeTransactions_acrossThePageBreak() throws Exception {
        StagingResponse response = generate();
        // 2 real transactions on page 1 + 1 on page 2 -- the page-footer line and the repeated
        // title banner/header on page 2 must be recognized as noise, not merged into a real row
        // or staged as a garbage row of their own.
        assertThat(response.rows()).hasSize(3);
    }

    @Test
    void generate_classifiesParenthesizedDrAndCrCorrectly() throws Exception {
        StagingResponse response = generate();

        var credit = response.rows().stream().filter(r -> r.description().contains("Salary Credit")
                && r.amount().compareTo(new java.math.BigDecimal("50000.00")) == 0).findFirst().orElseThrow();
        assertThat(credit.type()).isEqualTo("INCOME");

        var debit = response.rows().stream().filter(r -> r.description().contains("UPI Payment")).findFirst().orElseThrow();
        assertThat(debit.type()).isEqualTo("EXPENSE");
        assertThat(debit.amount()).isEqualByComparingTo("34000.00");
    }

    @Test
    void generate_doesNotLeakThePageFooterOrTitleBannerIntoARealTransaction() throws Exception {
        StagingResponse response = generate();

        assertThat(response.rows()).noneMatch(r -> r.description().contains("Page"));
        assertThat(response.rows()).noneMatch(r -> r.description().contains("Savings Account"));
    }

    @Test
    void generate_surfacesTheTitleBannerAsUnparseable_ratherThanSilentlyDroppingIt() throws Exception {
        // "Never lose information" (see the engineering principles doc): the page-2 title banner
        // ("Savings Account," no date, no amount) correctly never becomes a staged transaction --
        // but it also isn't just gone. It shows up here, with a specific, actionable reason, not
        // merely absent from the row count the way it would have been before this capability.
        StagingResponse response = generate();

        assertThat(response.unparseableRows()).isNotEmpty();
        var banner = response.unparseableRows().stream()
                .filter(r -> "Savings Account".equals(r.raw().get("Date")))
                .findFirst().orElseThrow();
        assertThat(banner.reason()).contains("didn't match any known date format");
    }

    @Test
    void generate_reconstructsBalanceChain_forASameDayTwoTransactionCluster() throws Exception {
        StagingResponse response = generate();
        var detected = response.detectedAccount();

        // 01-05: CR 50000.00 (balance 58234.84) then DR 34000.00 (balance 24234.84) -- true first
        // of that day implies an opening balance of 58234.84 - 50000.00 = 8234.84.
        assertThat(detected.openingBalance()).isEqualByComparingTo("8234.84");
        // 02-05 is a single-transaction day: CR 15000.00, balance 39234.84.
        assertThat(detected.closingBalance()).isEqualByComparingTo("39234.84");
        assertThat(detected.suggestedAccountType()).isEqualTo("SAVINGS");
    }
}
