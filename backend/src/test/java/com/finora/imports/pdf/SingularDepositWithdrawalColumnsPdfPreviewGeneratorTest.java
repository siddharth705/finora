package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagingResponse;
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
 * Singular "Withdrawal (Dr.)" / "Deposit (Cr.)" column headers, a day-abbreviated-month-year date
 * format, and a reward/cashback row whose amount is combined with the running balance in one
 * cell rather than getting its own column value. See
 * {@code PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample}'s own doc comment for the
 * real Kotak Mahindra Bank statement this is modeled on. Covers a genuinely serious bug found
 * against that file: rows didn't just fail to parse, they silently staged with the wrong amount
 * (the running balance instead of the actual transaction amount) and the wrong direction (every
 * row as EXPENSE, including real credits) -- see {@code TransactionNormalizer}'s own regression
 * tests for that half of the fix.
 */
class SingularDepositWithdrawalColumnsPdfPreviewGeneratorTest {

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
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();
        return realGenerator().generate(UUID.randomUUID(), "singular_deposit_withdrawal.pdf", bytes);
    }

    @Test
    void generate_extractsAllThreeRows_withTheDayAbbreviatedMonthYearDateFormat() throws Exception {
        StagingResponse response = generate();
        assertThat(response.rows()).hasSize(3);
    }

    @Test
    void generate_usesTheActualWithdrawalAmount_notTheRunningBalance() throws Exception {
        StagingResponse response = generate();

        var row = response.rows().stream().filter(r -> r.description().contains("Landlord")).findFirst().orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo("1000.00");
        assertThat(row.type()).isEqualTo("EXPENSE");
    }

    @Test
    void generate_usesTheActualDepositAmount_notTheRunningBalance() throws Exception {
        StagingResponse response = generate();

        var row = response.rows().stream().filter(r -> r.description().contains("SAMPLE PAYEE")).findFirst().orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo("10.00");
        assertThat(row.type()).isEqualTo("INCOME");
    }

    @Test
    void generate_splitsTheCashbackAmount_offOfTheCombinedBalanceCell() throws Exception {
        StagingResponse response = generate();

        var row = response.rows().stream().filter(r -> r.description().contains("CASHBACK")).findFirst().orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo("1.00");
        assertThat(row.type()).isEqualTo("INCOME");
    }
}
