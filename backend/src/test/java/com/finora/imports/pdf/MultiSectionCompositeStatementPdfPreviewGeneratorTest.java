package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

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
 * A multi-section "composite statement" layout: one PDF bundling a savings-account section and a
 * credit-card section, each introduced by its own marker line and each with its own,
 * differently-shaped header/table -- the case PdfPreviewGenerator.generateSections exists for.
 * Modeled on HSBC India's "Composite Statement," but the pattern isn't specific to that bank; any
 * bank could ship a multi-account PDF this shape.
 */
class MultiSectionCompositeStatementPdfPreviewGeneratorTest {

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

    private List<StagedAccountSection> generateSections() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildMultiSectionCompositeStatementSample();
        return realGenerator().generateSections(UUID.randomUUID(), "composite_statement.pdf", bytes);
    }

    @Test
    void generateSections_splitsTheDocumentIntoExactlyTwoAccountSections() throws Exception {
        List<StagedAccountSection> sections = generateSections();
        assertThat(sections).hasSize(2);
    }

    @Test
    void generateSections_detectsTheSavingsSectionFirst_withNoCreditCardRowLeakage() throws Exception {
        List<StagedAccountSection> sections = generateSections();
        StagedAccountSection savings = sections.get(0);

        assertThat(savings.detectedAccount().suggestedAccountType()).isEqualTo("SAVINGS");
        assertThat(savings.rows()).hasSize(2);
        assertThat(savings.rows()).noneMatch(r -> r.description().contains("Retailer"));

        var salary = savings.rows().stream().filter(r -> r.description().contains("Salary")).findFirst().orElseThrow();
        assertThat(salary.type()).isEqualTo("INCOME");
        assertThat(salary.amount()).isEqualByComparingTo("55000.00");

        var grocery = savings.rows().stream().filter(r -> r.description().contains("Grocery")).findFirst().orElseThrow();
        assertThat(grocery.type()).isEqualTo("EXPENSE");
        assertThat(grocery.amount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void generateSections_detectsTheCreditCardSectionSecond_fromItsOwnPaymentSummaryText() throws Exception {
        List<StagedAccountSection> sections = generateSections();
        StagedAccountSection creditCard = sections.get(1);

        assertThat(creditCard.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(creditCard.rows()).hasSize(1);
        assertThat(creditCard.rows().get(0).description()).contains("Retailer");
        assertThat(creditCard.rows().get(0).type()).isEqualTo("EXPENSE");
        assertThat(creditCard.rows().get(0).amount()).isEqualByComparingTo("1817.02");
    }

    /**
     * Bug fix: {@code TransactionTableDateRangeExtractor} is read once, document-wide -- correct for
     * a credit-card billing panel (a real card statement is effectively always one account), but this
     * extractor isn't restricted to credit-card documents. Applying its single, document-wide match to
     * EVERY section unconditionally would stamp the savings section's own printed range onto the
     * unrelated credit-card section too. Neither section here has any OTHER printed period, so the
     * honest answer for both is null, not the savings section's 05-Jul-to-10-Jul range copied onto
     * the credit-card section that never printed anything of the kind.
     */
    @Test
    void generateSections_doesNotCopyOneSectionsPrintedTableHeaderRangeOntoAnUnrelatedSection()
            throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildMultiSectionCompositeStatementWithTableHeaderDateRangeSample();
        List<StagedAccountSection> sections =
                realGenerator().generateSections(UUID.randomUUID(), "composite_statement.pdf", bytes);

        assertThat(sections).hasSize(2);
        StagedAccountSection creditCard = sections.get(1);
        assertThat(creditCard.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(creditCard.detectedAccount().statementPeriodStart())
                .as("the credit-card section printed no period of its own -- it must not silently "
                        + "inherit the savings section's")
                .isNull();
        assertThat(creditCard.detectedAccount().statementPeriodEnd()).isNull();
    }
}
