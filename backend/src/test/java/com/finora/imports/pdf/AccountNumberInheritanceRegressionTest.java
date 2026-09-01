package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfTrace;
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
 * Real-document proof for {@link PdfPreviewGenerator#inheritAccountNumberAcrossSections}: two real
 * credit-card statements (SBI, IndusInd) whose second section -- a malformed rewards/purchase-detail
 * fragment {@link PdfTableLocator} splits into its own {@code UNKNOWN}-product section -- never
 * finds its own {@code accountNumberMasked}, and now inherits it from the sibling section that did.
 */
class AccountNumberInheritanceRegressionTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void sbisSecondSectionInheritsTheFirstSectionsCardNumber() {
        List<StagedAccountSection> sections = generate("sbi-credit-card-statement");
        assertSecondSectionInherited(sections);
    }

    @Test
    void induslandsSecondSectionInheritsTheFirstSectionsCardNumber() {
        List<StagedAccountSection> sections = generate("indusland-credit-card-account-number-inheritance");
        assertSecondSectionInherited(sections);
    }

    private void assertSecondSectionInherited(List<StagedAccountSection> sections) {
        assertThat(sections).hasSizeGreaterThanOrEqualTo(2);
        String sourceAccountNumber = sections.get(0).detectedAccount().accountNumberMasked();
        assertThat(sourceAccountNumber).isNotNull();

        StagedAccountSection unknownSection = sections.stream()
                .filter(s -> "UNKNOWN".equals(s.detectedAccount().detectedProduct()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an UNKNOWN-product sibling section"));
        assertThat(unknownSection.detectedAccount().accountNumberMasked()).isEqualTo(sourceAccountNumber);
    }

    private record TraceAcquirer(List<PositionedText> runs) implements DocumentTextAcquirer {
        TraceAcquirer(String trace) { this(PdfTrace.load(trace)); }
        @Override public AcquiredDocument acquire(byte[] fileBytes, String password) { return AcquiredDocument.of(runs); }
        @Override public boolean supports(byte[] fileBytes) { return true; }
    }

    private List<StagedAccountSection> generate(String trace) {
        try {
            return generatorFor(trace).generateSections(userId, trace + ".pdf", new byte[]{1}, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PdfPreviewGenerator generatorFor(String trace) {
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
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        return new PdfPreviewGenerator(new TraceAcquirer(trace), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
    }
}
