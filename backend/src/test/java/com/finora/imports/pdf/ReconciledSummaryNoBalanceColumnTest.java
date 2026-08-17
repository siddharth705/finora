package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.*;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
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
 * C-8.1 fixture, the missing consuming test: {@link PdfFixtureBuilder#buildReconciledSummaryNoBalanceColumnSample}
 * was added without one, leaving its own doc comment's claims unverified. Same pipeline-wiring
 * pattern as {@link UnreadableStatementKeepsItsEvidenceTest}, the sibling this fixture's own doc
 * comment contrasts itself against.
 *
 * <p>The scenario this proves: native extraction succeeds completely, the parsed rows reconcile
 * exactly against the bank's own printed summary totals ({@link SummaryTotalsValidator} genuinely
 * VERIFIES, not merely goes unreached), and yet {@link StatementTotalsValidator} -- which checks a
 * printed closing balance against the running balance the ledger itself implies -- has nothing to
 * check at all, because this ledger has no running-balance column. NOT_APPLICABLE is the honest
 * outcome for that: the document is not suspicious, the check simply doesn't apply to it.
 */
class ReconciledSummaryNoBalanceColumnTest {

    private PdfPreviewGenerator generator() {
        CategorizationService cat = mock(CategorizationService.class);
        when(cat.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(cat, new DuplicateDetector(repo), TestRuleEngines.empty()),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator()),
                TestRuleEngines.empty());
    }

    private StagedAccountSection onlySectionOf(byte[] pdf) throws Exception {
        var sections = generator().generateSectionsWithContext(UUID.randomUUID(), "s.pdf", pdf, null).sections();
        assertThat(sections).hasSize(1);
        return sections.get(0);
    }

    private VerificationFinding findingFor(StagedAccountSection section, String rule) {
        assertThat(section.verification()).isNotNull();
        return section.verification().findings().stream()
                .filter(f -> rule.equals(f.rule()))
                .findFirst().orElseThrow(() -> new AssertionError("no finding for rule " + rule));
    }

    @Test
    void nativeExtractionRecoversEveryRow_despiteTheMissingBalanceColumn() throws Exception {
        var section = onlySectionOf(PdfFixtureBuilder.buildReconciledSummaryNoBalanceColumnSample());

        assertThat(section.rows())
                .as("the fixture's premise: extraction succeeds completely, nothing is lost to the missing column")
                .hasSize(3);
    }

    @Test
    void theRowsReconcile_againstTheBanksOwnPrintedSummaryTotals() throws Exception {
        var section = onlySectionOf(PdfFixtureBuilder.buildReconciledSummaryNoBalanceColumnSample());

        var finding = findingFor(section, SummaryTotalsValidator.RULE);

        assertThat(finding.outcome())
                .as("a genuine, independent internal check that actually ran and agreed -- not merely unreached")
                .isEqualTo("VERIFIED");
    }

    @Test
    void statementTotalsIsNotApplicable_notAFailure_whenThereIsNoBalanceColumnToCheck() throws Exception {
        var section = onlySectionOf(PdfFixtureBuilder.buildReconciledSummaryNoBalanceColumnSample());

        var finding = findingFor(section, StatementTotalsValidator.RULE);

        assertThat(finding.outcome())
                .as("no closing balance exists anywhere in this document for this check to compare against -- "
                        + "that is a legitimately inapplicable check, not a suspicious or failing one")
                .isEqualTo("NOT_APPLICABLE");
    }
}
