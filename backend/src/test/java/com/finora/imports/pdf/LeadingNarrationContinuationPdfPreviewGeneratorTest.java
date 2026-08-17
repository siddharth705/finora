package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LEADING_NARRATION_CONTINUATION: a transaction's narration wraps across several lines BEFORE its
 * own date+amount row, not after -- the reverse of {@code WrappedDescriptionCreditCardPdfPreviewGeneratorTest}'s
 * shape. See {@code PdfFixtureBuilder.buildLeadingNarrationContinuationSample}'s own doc comment
 * for the real Canara Bank statement this is modeled on, and {@code PdfTableLocator}'s
 * {@code MAX_TRAILING_CONTINUATION_ROWS}/{@code pendingLeading} for the implementation. Before this
 * capability existed, every transaction's leading narration silently attached to the WRONG
 * transaction (the previous one, or the statement's own "Opening Balance" summary row) instead of
 * its own.
 */
class LeadingNarrationContinuationPdfPreviewGeneratorTest {

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

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildLeadingNarrationContinuationSample();
        return realGenerator().generate(UUID.randomUUID(), "leading_narration.pdf", bytes);
    }

    @Test
    void generate_extractsBothRealTransactions() throws Exception {
        StagingResponse response = generate();
        assertThat(response.rows()).hasSize(2);
    }

    @Test
    void generate_attachesLeadingAndTrailingNarration_toTheCorrectTransaction_notTheAdjacentOne() throws Exception {
        StagingResponse response = generate();

        var first = response.rows().stream().filter(r -> r.date().equals(LocalDate.of(2026, 7, 15))).findFirst().orElseThrow();
        assertThat(first.description()).contains("JOHN DOE").contains("REF123456");
        assertThat(first.description()).doesNotContain("JANE SMITH").doesNotContain("REF789012");
        assertThat(first.amount()).isEqualByComparingTo("500.00");
        assertThat(first.type()).isEqualTo("INCOME");

        var second = response.rows().stream().filter(r -> r.date().equals(LocalDate.of(2026, 7, 16))).findFirst().orElseThrow();
        assertThat(second.description()).contains("JANE SMITH").contains("REF789012");
        assertThat(second.description()).doesNotContain("JOHN DOE").doesNotContain("REF123456");
        assertThat(second.amount()).isEqualByComparingTo("200.00");
        assertThat(second.type()).isEqualTo("EXPENSE");
    }

    @Test
    void generate_doesNotPolluteTheOpeningBalanceRow_withTheFirstTransactionsLeadingNarration() throws Exception {
        StagingResponse response = generate();

        var openingBalanceRow = response.unparseableRows().stream()
                .filter(r -> "Opening Balance".equals(r.raw().get("Deposits")))
                .findFirst().orElseThrow();
        assertThat(openingBalanceRow.raw().get("Particulars")).isNull();
    }
}
