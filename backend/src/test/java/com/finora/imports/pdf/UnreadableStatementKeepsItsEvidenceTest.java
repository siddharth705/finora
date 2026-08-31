package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

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
 * A document whose table could not be read must keep the evidence that says so.
 *
 * <p>Two defects met here, and only together did they produce silence. The no-table path rebuilt
 * its section with the five-argument constructor, which defaults {@code verification} to null and
 * so discarded the report that had just been computed; and the same path passed
 * {@code PrintedSummary.NONE}, so even a preserved report would have had nothing to say. A real SBI
 * statement printing "Dr Count 5 / Cr Count 1 / Total Debits 5,000.00 / Total Credits 40,000.00"
 * reached the user having staged nothing and carrying no verification report at all.
 *
 * <p>That is the failure the integrity rule exists to prevent: the import completed, and nothing in
 * the result said the statement's own figures disagreed with what was accepted.
 */
class UnreadableStatementKeepsItsEvidenceTest {

    private PdfPreviewGenerator generator() {
        CategorizationService cat = mock(CategorizationService.class);
        when(cat.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(cat, new DuplicateDetector(repo, TestAccountRepositories.anyLive()), TestRuleEngines.empty()),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
    }

    private StagedAccountSection onlySectionOf(byte[] pdf) throws Exception {
        var sections = generator().generateSectionsWithContext(UUID.randomUUID(), "s.pdf", pdf, null).sections();
        assertThat(sections).hasSize(1);
        return sections.get(0);
    }

    private VerificationFinding summaryFinding(StagedAccountSection section) {
        assertThat(section.verification())
                .as("a section that parsed nothing still has to carry its verification report")
                .isNotNull();
        return section.verification().findings().stream()
                .filter(f -> SummaryTotalsValidator.RULE.equals(f.rule()))
                .findFirst().orElseThrow();
    }

    @Test
    void aStatementThatPrintsActivityButStagesNothingWarnsWithItsEvidence() throws Exception {
        var section = onlySectionOf(PdfFixtureBuilder.buildPrintedSummaryNoReadableTableSample());

        assertThat(section.rows()).as("the fixture's premise: nothing reached the ledger").isEmpty();

        var finding = summaryFinding(section);
        assertThat(finding.outcome())
                .as("not NOT_APPLICABLE -- the statement's own figures are evidence, and they disagree")
                .isEqualTo("WARNING");
        assertThat(finding.details())
                .containsEntry("suspectedCause", SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED)
                .containsEntry("stagedTransactionCount", 0)
                .containsEntry("printedDebitCount", 5)
                .containsEntry("printedCreditCount", 1);
    }

    @Test
    void theEvidenceIsStructured_notASentenceToBeParsed() throws Exception {
        var finding = summaryFinding(onlySectionOf(
                PdfFixtureBuilder.buildPrintedSummaryNoReadableTableSample()));

        // A consumer -- the UI, the audit trail, corpus tooling, a support engineer -- must be able
        // to learn what happened without reading prose. The sentence is presentation.
        assertThat(finding.details().keySet())
                .contains("suspectedCause", "stagedTransactionCount", "locatedRowCount");
        assertThat(finding.details().get("suspectedCause"))
                .isInstanceOf(String.class)
                .isEqualTo("PRINTED_ACTIVITY_WITH_ZERO_STAGED_TRANSACTIONS");
    }

    @Test
    void theUnreadableTextIsStillReported_soNothingIsLost() throws Exception {
        var section = onlySectionOf(PdfFixtureBuilder.buildPrintedSummaryNoReadableTableSample());

        assertThat(section.unparseableRows())
                .as("never lose information: the lines that could not be read are still surfaced")
                .isNotEmpty();
    }
}
