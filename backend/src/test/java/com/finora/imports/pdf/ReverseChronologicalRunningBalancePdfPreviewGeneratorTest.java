package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A savings-account statement layout with an explicit Type (DR/CR) column and a running balance,
 * listed newest-first with a same-day 3-transaction cluster on one of the statement's boundary
 * dates -- exactly the shape that motivated BalanceChainUtil. Modeled on PNB ONE's export, but
 * the pattern isn't specific to that bank; any statement generator could list transactions this
 * same way.
 */
class ReverseChronologicalRunningBalancePdfPreviewGeneratorTest {

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

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        return realGenerator().generate(UUID.randomUUID(), "reverse_chronological_statement.pdf", bytes);
    }

    @Test
    void generate_extractsAllSixRows_andClassifiesDrCrFromTheTypeColumn() throws Exception {
        StagingResponse response = generate();
        assertThat(response.rows()).hasSize(6);
        assertThat(response.rows()).allSatisfy(r -> {
            if (r.description().contains("Aman Kum")) assertThat(r.type()).isEqualTo("INCOME");
            else assertThat(r.type()).isEqualTo("EXPENSE");
        });
    }

    @Test
    void generate_returnsRowsInChronologicalOrder_despiteTheFileListingNewestFirst() throws Exception {
        StagingResponse response = generate();

        // File order is 26/07 -> 25/07 (x3) -> 19/07 -> 18/07 (newest-first, like the real
        // export) -- staged rows must come back oldest-first for a sane review-table display.
        assertThat(response.rows()).isSortedAccordingTo(Comparator.comparing(StagedRow::date));
        assertThat(response.rows().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(response.rows().get(response.rows().size() - 1).date()).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void generate_reconstructsOpeningAndClosingBalance_fromTheBalanceChain_notFileOrder() throws Exception {
        StagingResponse response = generate();
        var detected = response.detectedAccount();

        // Opening balance: the earliest date's true first transaction is the 18/07 CR 1057.0 row
        // (balance 12747.27) -- implied balance before it is 12747.27 - 1057.0 = 11690.27.
        assertThat(detected.openingBalance()).isEqualByComparingTo("11690.27");
        // Closing balance: 26/07 is a single-transaction day, balance 10075.86 after it.
        assertThat(detected.closingBalance()).isEqualByComparingTo("10075.86");
    }

    @Test
    void generate_detectsSavingsAccountType_andResolvesTheBankFromTheIfscLine() throws Exception {
        StagingResponse response = generate();
        var detected = response.detectedAccount();

        assertThat(detected.suggestedAccountType()).isEqualTo("SAVINGS");
        assertThat(detected.bank().id()).isEqualTo("PNB");
    }
}
