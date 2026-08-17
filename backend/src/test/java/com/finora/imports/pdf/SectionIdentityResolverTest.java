package com.finora.imports.pdf;

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
 * Section Identity Resolver (Layer 2 of the composite-account-merge fix -- see
 * {@code PdfTableLocator}'s own {@code ACCOUNT_IDENTITY_LINE} doc comment for Layer 1, and
 * {@code PdfPreviewGenerator.resolveSectionIdentities} for this layer). Layer 1 guarantees it
 * never silently MERGES two accounts, using only structural/text-shape signals, but that same
 * caution means it can over-split one real account -- a formatting quirk that makes its raw
 * string comparison disagree even though the account is the same. This layer reconciles that
 * using the real identity/product/institution evidence Layer 1 never has access to, and does so
 * conservatively: it folds a pair back together ONLY on positive evidence of sameness, never on
 * an absence of evidence either way.
 */
class SectionIdentityResolverTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
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
                        new com.finora.imports.ColumnAmbiguityValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void sameAccountFormattedDifferentlyAcrossPages_isReconciledIntoOneSection() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildSameAccountReconciledAcrossFormattingSample();
        // Filename carries the bank signal deliberately -- BankRegistry.detect falls back to it
        // (Signal 4) when neither section's own text states the bank by name, which is what lets
        // BOTH sections resolve to the SAME institution despite splitting apart structurally.
        PdfPreviewGenerator.PdfGenerationResult result =
                realGenerator().generateSectionsWithContext(UUID.randomUUID(), "hdfc_statement.pdf", bytes);

        assertThat(result.sections())
                .as("Layer 1 over-splits this on raw string disagreement; Layer 2 must fold it back")
                .hasSize(1);
        StagedAccountSection section = result.sections().get(0);
        assertThat(section.rows()).as("all four rows survive the fold, combined and in order").hasSize(4);
        assertThat(section.rows().stream().map(r -> r.description()).toList())
                .containsExactly("Page1 txn 1", "Page1 txn 2", "Page2 txn 1", "Page2 txn 2");
        assertThat(section.detectedAccount().accountNumberMasked())
                .as("the folded section keeps a real, resolved identity")
                .isEqualTo("••••9012");
        assertThat(result.documentContext().capabilities().stream().map(c -> c.capability()))
                .as("a genuine fold, not a shrug -- COMPOSITE_STATEMENT fired (Layer 1 did split), "
                        + "AMBIGUOUS did not (Layer 2 was confident, not just permissive)")
                .contains("COMPOSITE_STATEMENT")
                .doesNotContain("SECTION_IDENTITY_AMBIGUOUS");
    }

    @Test
    void insufficientEvidenceOnEitherSide_staysSplit_andIsRecordedAsAmbiguous() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildAmbiguousSectionSplitSample();
        PdfPreviewGenerator.PdfGenerationResult result =
                realGenerator().generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", bytes);

        assertThat(result.sections())
                .as("neither SAME_ACCOUNT nor DIFFERENT_ACCOUNT can be confirmed -- never guessed into a merge")
                .hasSize(2);
        assertThat(result.sections().get(0).rows()).hasSize(2);
        assertThat(result.sections().get(1).rows()).hasSize(2);
        assertThat(result.documentContext().capabilities().stream().map(c -> c.capability()))
                .contains("SECTION_IDENTITY_AMBIGUOUS");
    }

    /**
     * Adversarial review's own most important finding: a PROBABLE-strength match (same masked
     * digits, same product type, no strong key on either side to settle it) must NEVER fold, even
     * though the first version of {@code compareSectionIdentity} treated EXACT and PROBABLE
     * identically. {@link com.finora.imports.product.ProductIdentity}'s own doc comment for
     * {@code Match#PROBABLE} is explicit -- "this goes to the user, never to a silent merge" --
     * and two accounts at the same bank sharing a masked last-4 is, in that same class's own
     * words, "entirely ordinary," not proof of sameness. Folding here would silently merge two
     * genuinely different accounts' transaction histories on a coincidence: exactly the P0 this
     * whole two-layer fix exists to prevent, reopened by this layer instead of closed by it.
     */
    @Test
    void probableMatchAlone_mustNotFold_evenThoughItWouldHavePassedTheFirstVersionOfThisCheck() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildProbableMatchOnlyMustNotFoldSample();
        PdfPreviewGenerator.PdfGenerationResult result =
                realGenerator().generateSectionsWithContext(UUID.randomUUID(), "hdfc_statement.pdf", bytes);

        assertThat(result.sections())
                .as("PROBABLE is plausible, not proven -- never enough to merge on its own")
                .hasSize(2);
        assertThat(result.sections().get(0).detectedAccount().accountNumberMasked())
                .as("both sides genuinely share the same masked digits -- the coincidence is real")
                .isEqualTo(result.sections().get(1).detectedAccount().accountNumberMasked())
                .isEqualTo("••••1234");
        assertThat(result.documentContext().capabilities().stream().map(c -> c.capability()))
                .contains("SECTION_IDENTITY_AMBIGUOUS");
    }
}
