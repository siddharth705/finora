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
 * SUMMARY_ATTRIBUTION: which section a document-level printed summary is allowed to be about.
 *
 * <p>A printed summary describes the whole file. Attributing it to a section is a guess unless the
 * document leaves exactly one candidate, and checking the wrong section's rows against another
 * section's totals would manufacture a failure out of a correct import.
 *
 * <p>The rule is one condition — <b>exactly one section ended up with transactions</b> — and these
 * tests exist to prove that condition rather than the happy path. Anything weaker (the first
 * section, the largest, the one with most rows) passes the positive case below and fails the
 * two-transactional case, which is the pair that matters.
 *
 * <p>Evidence: on the real HDFC combined statement this exists for, four sections carry one
 * transactional ledger and three empty deposit schedules, and the printed counts and totals match
 * that ledger exactly — yet SUMMARY_TOTALS reported NOT_APPLICABLE. The corpus comparison for this
 * change moves that one document from NOT_APPLICABLE to VERIFIED and leaves the other seventeen
 * untouched. The two-transactional-section case has no corpus document at all, so its fixture is
 * built rather than captured, and is marked synthetic-only in the coverage matrix.
 */
class SummaryAttributionPdfPreviewGeneratorTest {

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

    private List<StagedAccountSection> sectionsOf(byte[] pdf) throws Exception {
        return generator().generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf, null).sections();
    }

    private String summaryOutcome(StagedAccountSection section) {
        if (section.verification() == null) return "NO_REPORT";
        return section.verification().findings().stream()
                .filter(f -> SummaryTotalsValidator.RULE.equals(f.rule()))
                .map(VerificationFinding::outcome)
                .findFirst().orElse("ABSENT");
    }

    private List<StagedAccountSection> transactional(List<StagedAccountSection> sections) {
        return sections.stream().filter(s -> s.rows() != null && !s.rows().isEmpty()).toList();
    }

    @Test
    void attributesTheSummaryWhenExactlyOneSectionHasTransactions() throws Exception {
        var sections = sectionsOf(PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample());

        var withRows = transactional(sections);
        assertThat(withRows).as("the fixture's premise: one ledger, the rest are deposit schedules").hasSize(1);
        assertThat(summaryOutcome(withRows.get(0)))
                .as("printed 2 debits of 3,404.91 and 1 credit of 55,000.00 -- which is what was parsed")
                .isEqualTo("VERIFIED");
    }

    @Test
    void doesNotAttributeWhenTwoSectionsHaveTransactions() throws Exception {
        var sections = sectionsOf(PdfFixtureBuilder.buildSummaryWithTwoTransactionalSectionsSample());

        var withRows = transactional(sections);
        assertThat(withRows).as("the fixture's premise: a savings ledger AND a credit-card ledger").hasSize(2);
        // The totals describe the first section exactly. A rule that picked the first, the largest
        // or the most populated would VERIFY here -- and be wrong, because a document-level summary
        // on a genuine two-account statement describes neither section on its own.
        assertThat(withRows).allSatisfy(s -> assertThat(summaryOutcome(s))
                .as("no section may claim a summary that could belong to the other")
                .isEqualTo("NOT_APPLICABLE"));
    }

    @Test
    void aSectionWithNoTransactionsIsNeverGivenTheSummary() throws Exception {
        var sections = sectionsOf(PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample());

        assertThat(sections.stream().filter(s -> s.rows() == null || s.rows().isEmpty()))
                .allSatisfy(s -> assertThat(summaryOutcome(s))
                        .as("an empty section has nothing the totals could be checked against")
                        .isIn("NOT_APPLICABLE", "NO_REPORT", "ABSENT"));
    }

    @Test
    void aDocumentThatPrintsNoSummaryStillReportsHonestly() throws Exception {
        // The composite multi-product statement carries no debit/credit totals at all.
        var sections = sectionsOf(PdfFixtureBuilder.buildCompositeMultiProductStatementSample());

        assertThat(transactional(sections)).as("one ledger, two deposit schedules").hasSize(1);
        assertThat(summaryOutcome(transactional(sections).get(0)))
                .as("nothing printed, so nothing to attribute")
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void attributionChangesOnlyTheSummaryRule_neverAnotherFinding() throws Exception {
        // Revision replaces one finding in place. Every other rule's outcome, and the order they
        // are reported in, must survive it -- a report is assembled, not aggregated.
        var attributed = transactional(sectionsOf(
                PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample())).get(0);
        var notAttributed = transactional(sectionsOf(
                PdfFixtureBuilder.buildCompositeMultiProductStatementSample())).get(0);

        assertThat(attributed.verification().findings()).extracting(VerificationFinding::rule)
                .as("same rules, same order, whether or not a summary was attributed")
                .isEqualTo(notAttributed.verification().findings().stream()
                        .map(VerificationFinding::rule).toList());
    }
}
