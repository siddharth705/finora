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
 * A credit-card statement layout with a combined date-and-time column -- a leading "+" marks a
 * credit, and a transaction's description can continue onto a second, dateless/amountless visual
 * row that must fold into the row above it. Modeled on HDFC's "Tata Neu Plus" statement, but the
 * pattern isn't specific to that bank; any card issuer could wrap descriptions this same way.
 */
class WrappedDescriptionCreditCardPdfPreviewGeneratorTest {

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
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildWrappedDescriptionCreditCardSample();
        return realGenerator().generate(UUID.randomUUID(), "wrapped_description_statement.pdf", bytes);
    }

    @Test
    void generate_foldsTheContinuationLineIntoThePrecedingRow_ratherThanDroppingOrDuplicating() throws Exception {
        StagingResponse response = generate();

        // Exactly 2 real transactions -- the dateless/amountless continuation line must fold into
        // the row above it, not survive as its own (unparseable, silently dropped) third row.
        assertThat(response.rows()).hasSize(2);

        var payment = response.rows().stream().filter(r -> r.description().contains("BPPY CC PAYMENT")).findFirst().orElseThrow();
        assertThat(payment.description()).contains("Ref# ST000000000000000000");
        assertThat(payment.date()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void generate_treatsALeadingPlusAsACredit_andAPlainAmountAsAnExpense() throws Exception {
        StagingResponse response = generate();

        var payment = response.rows().stream().filter(r -> r.description().contains("BPPY CC PAYMENT")).findFirst().orElseThrow();
        assertThat(payment.type()).isEqualTo("INCOME");
        assertThat(payment.amount()).isEqualByComparingTo("355.00");

        var purchase = response.rows().stream().filter(r -> r.description().contains("Retailer")).findFirst().orElseThrow();
        assertThat(purchase.type()).isEqualTo("EXPENSE");
        assertThat(purchase.amount()).isEqualByComparingTo("942.50");
        assertThat(purchase.date()).isEqualTo(LocalDate.of(2026, 7, 11));
    }

    @Test
    void generate_detectsCreditCardAccountType() throws Exception {
        StagingResponse response = generate();

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
    }
}
