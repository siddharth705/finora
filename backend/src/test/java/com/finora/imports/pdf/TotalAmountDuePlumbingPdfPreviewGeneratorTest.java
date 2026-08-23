package com.finora.imports.pdf;

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
 * Phase 1B: {@code CreditCardSummaryEvidence.totalAmountDue} was already correctly detected by
 * {@link CreditCardSummaryExtractor} but went no further -- {@link CreditCardStatementTotalsValidator}
 * read it and nothing else did, so it never reached {@link com.finora.dto.ImportDto.DetectedAccountInfo},
 * the API, or the review screen. This is the plumbing, not a new detection capability: the same
 * evidence {@code buildLedgerSection} already threads to {@code importVerifier.verify(...)} now
 * also reaches {@code buildDetectedAccountInfo}.
 */
class TotalAmountDuePlumbingPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    /**
     * A payment-summary panel in the same-row label/value grid shape {@code CreditCardSummaryExtractor}
     * already reads today (see {@code CreditCardSummaryExtractorTest.sameRowSummaryBlock}); this test
     * proves that value now survives all the way into {@code DetectedAccountInfo}, not just into the
     * verification report {@code CreditCardStatementTotalsValidator} already consumed it for.
     */
    @Test
    void aCreditCardStatementsTotalAmountDue_reachesDetectedAccountInfo() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildCreditCardTotalDueGridSample();

        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "credit_card_total_due_statement.pdf", pdf);

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(response.detectedAccount().totalAmountDue()).isEqualByComparingTo("27665.16");
    }

    /**
     * A savings account has no payment-summary panel for {@code CreditCardSummaryExtractor} to
     * read at all -- {@code totalAmountDue} must stay null rather than picking up an unrelated
     * figure from the statement (a transaction amount, a running balance, ...).
     */
    @Test
    void aNonCreditCardStatement_leavesTotalAmountDueNull() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildIncidentalCardNumberSecurityNoticeSample();

        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "savings_with_security_notice.pdf", pdf);

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("SAVINGS");
        assertThat(response.detectedAccount().totalAmountDue()).isNull();
    }
}
