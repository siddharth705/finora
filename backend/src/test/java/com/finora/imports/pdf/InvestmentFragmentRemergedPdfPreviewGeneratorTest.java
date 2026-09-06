package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TestRuleEngines;
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
 * A real deposit product routinely prints its own summary (rate, principal-equivalent amount,
 * maturity) and its own installment schedule as TWO structurally different tables under one
 * logical account, with no identity banner tying them together -- so {@link PdfTableLocator},
 * which has no notion of product type at all, stages them as two independent sections. The
 * summary alone usually classifies confidently; the schedule alone often does not, because the
 * two tables' expected signals never repeat on both, capping its own standalone confidence below
 * threshold and falling to UNKNOWN -- whose {@code hasTransactions()==false} then means the
 * schedule's own real rows never stage, even though they were correctly extracted. {@link
 * PdfPreviewGenerator#mergeOrphanedInvestmentFragments} folds the two sections' raw content back
 * together before classification runs, so the ordinary pipeline sees every signal at once.
 *
 * <p>Uses {@link PdfFixtureBuilder#buildOrphanedInvestmentScheduleSample()} -- coordinates and
 * shapes only, per the Synthetic Fixture Policy; every value is invented.
 */
class InvestmentFragmentRemergedPdfPreviewGeneratorTest {

    private PdfPreviewGenerator pdfGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer =
                new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
    }

    @Test
    void recurringDepositSummaryAndInstallmentSchedule_remergeAndClassifyTogether() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildOrphanedInvestmentScheduleSample();
        PdfPreviewGenerator.PdfGenerationResult result =
                pdfGenerator().generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf);

        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("INVESTMENT_FRAGMENT_REMERGED");

        List<StagedAccountSection> sections = result.sections();
        assertThat(sections).as("the summary and schedule must merge into one staged section").hasSize(1);
        assertThat(sections.get(0).detectedAccount().detectedProduct()).isEqualTo("RECURRING_DEPOSIT");
    }
}
