package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verification survives the {@code StagedAccountSection -> StagingResponse} conversion inside
 * {@link PdfPreviewGenerator#generate}.
 *
 * <p>See docs/architecture/system-design/pdfpreviewgenerator-verification-loss-investigation.md.
 * {@code generate()} is a single-account convenience wrapper over {@code generateSections} and had
 * the fully-populated section in hand at the moment it built the response — then built that
 * response with the five-argument {@code StagingResponse} overload, which defaults verification to
 * {@code null}. Every rule that ran was discarded one line after it was computed.
 *
 * <p>Every pre-existing assertion on a PDF's verification in this suite is made at the
 * {@code StagedAccountSection} level — i.e. UPSTREAM of that conversion — which is exactly why
 * nothing caught it. These assertions are deliberately made on the {@code StagingResponse}, the
 * object the API actually returns.
 */
class SingleAccountPdfKeepsItsVerificationTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService,
                duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer,
                com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(),
                        new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private byte[] singleAccountFixture() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
    }

    /** The loss itself: the response carries the report the section computed, not null. */
    @Test
    void generate_returnsTheVerificationReportItsOwnSectionComputed() throws Exception {
        UUID userId = UUID.randomUUID();
        String name = "separate_debit_credit_balance_statement.pdf";
        byte[] bytes = singleAccountFixture();

        StagedAccountSection section = realGenerator().generateSections(userId, name, bytes).get(0);
        StagingResponse response = realGenerator().generate(userId, name, bytes);

        assertThat(section.verification())
                .as("precondition: the section this wrapper reads from does compute a report")
                .isNotNull();
        assertThat(response.verification())
                .as("the same report reaches the response the API returns")
                .isNotNull();
        assertThat(response.verification().findings())
                .extracting(com.finora.dto.ImportDto.VerificationFinding::rule)
                .containsExactlyElementsOf(section.verification().findings().stream()
                        .map(com.finora.dto.ImportDto.VerificationFinding::rule).toList());
        assertThat(response.verification().findings())
                .extracting(com.finora.dto.ImportDto.VerificationFinding::outcome)
                .containsExactlyElementsOf(section.verification().findings().stream()
                        .map(com.finora.dto.ImportDto.VerificationFinding::outcome).toList());
    }

    /**
     * A real finding, not merely a non-null object: this fixture's balance chain is intact, so
     * BALANCE_CHAIN must be present and must not be reported as a failure. The point is that the
     * response can now distinguish "checked and clean" from "never checked" — before the fix both
     * looked identical to the frontend, which renders nothing at all for a null report.
     */
    @Test
    void generate_carriesTheActualRuleOutcomes_notJustANonNullEnvelope() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(),
                "separate_debit_credit_balance_statement.pdf", singleAccountFixture());

        assertThat(response.verification()).isNotNull();
        var balanceChain = response.verification().findings().stream()
                .filter(f -> "BALANCE_CHAIN".equals(f.rule())).findFirst().orElseThrow();
        assertThat(balanceChain.outcome()).isNotEqualTo("FAILED");
        assertThat(balanceChain.outcome()).isNotEqualTo("WARNING");
    }

    /**
     * The contrast case that proves the loss was specific to the single-account conversion rather
     * than systemic: the multi-section path returns its sections directly, with no conversion, and
     * always retained its reports. Asserted here so the fix can be shown not to have altered it.
     */
    @Test
    void generateSections_multiSectionComposite_stillCarriesAReportPerSection() throws Exception {
        List<StagedAccountSection> sections = realGenerator().generateSections(UUID.randomUUID(),
                "composite_statement.pdf", PdfFixtureBuilder.buildMultiSectionCompositeStatementSample());

        assertThat(sections).hasSize(2);
        assertThat(sections).allSatisfy(s -> assertThat(s.verification()).isNotNull());
    }

    /**
     * Degenerate but real: a document where nothing was recognized still produces a section, and
     * whatever verification that section carries — a report or a genuine null — must be passed
     * through as-is rather than being replaced by a default. This is the case that would expose an
     * NPE if the fix had reached into the report instead of copying the reference.
     */
    @Test
    void generate_passesThroughWhateverTheSectionCarries_evenForAnUnrecognizableDocument() throws Exception {
        UUID userId = UUID.randomUUID();
        byte[] bytes = PdfFixtureBuilder.buildUnrecognizableDocumentSample();

        StagedAccountSection section = realGenerator().generateSections(userId, "unrecognized.pdf", bytes).get(0);
        StagingResponse response = realGenerator().generate(userId, "unrecognized.pdf", bytes);

        if (section.verification() == null) {
            assertThat(response.verification()).isNull();
        } else {
            assertThat(response.verification()).isNotNull();
            assertThat(response.verification().findings())
                    .hasSameSizeAs(section.verification().findings());
        }
    }
}
