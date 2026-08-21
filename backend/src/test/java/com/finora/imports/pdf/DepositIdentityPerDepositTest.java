package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two deposits listed under one section's account number must not share one identity.
 *
 * A section's account number is the CUSTOMER's relationship number -- it appears once in the
 * section's metadata and says nothing about which of the deposits listed underneath it is which.
 * Deriving every deposit's identity from it alone gave them all the same key, and the consequence
 * was not a cosmetic duplicate: {@code ProductIdentityResolver} finds exactly one EXACT match for
 * the second deposit, {@code mayImportWithoutAsking()} returns true, and confirm() silently
 * redirects it into the first deposit's account instead of creating it. The second deposit
 * disappears.
 *
 * That is the mirror image of the double-counting identity exists to prevent, and worse: a
 * duplicate is visible in the UI, a deposit that was never created is not.
 */
class DepositIdentityPerDepositTest {

    private static final String BANK = "HDFC";
    private static final String RELATIONSHIP_NUMBER = "10000000000001";

    /** The two deposits from the composite fixture, as they would be keyed with a real bank and a
     *  section-level account number in play. */
    private ProductIdentity depositWith(BigDecimal principal, LocalDate maturity) {
        return ProductIdentity.of(BANK, FinancialProductType.FIXED_DEPOSIT,
                RELATIONSHIP_NUMBER, "0001",
                ProductIdentity.forDeposit(principal, maturity, null));
    }

    @Test
    void twoDepositsSharingOneAccountNumberGetDistinctIdentities() {
        ProductIdentity first = depositWith(new BigDecimal("100000.00"), LocalDate.of(2027, 3, 12));
        ProductIdentity second = depositWith(new BigDecimal("24053.00"), LocalDate.of(2027, 5, 1));

        assertThat(first.strongKey()).isNotNull();
        assertThat(first.strongKey()).isNotEqualTo(second.strongKey());
        assertThat(first.matches(second))
                .as("distinct deposits must not resolve as the same product")
                .isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void theSameDepositIsRecognisedAgainNextMonth() {
        // Distinct is not enough -- it must also be REPEATABLE, or a re-import creates a second copy
        // of every deposit, which is the failure identity was introduced to prevent.
        ProductIdentity june = depositWith(new BigDecimal("100000.00"), LocalDate.of(2027, 3, 12));
        ProductIdentity july = depositWith(new BigDecimal("100000.00"), LocalDate.of(2027, 3, 12));

        assertThat(june.matches(july)).isEqualTo(ProductIdentity.Match.EXACT);
    }

    @Test
    void thesameAmountPrintedWithDifferentPrecisionIsTheSameDeposit() {
        // "5000" and "5000.00" are the same principal; BigDecimal.toString keeps scale, so without
        // normalisation the same deposit would key differently depending on how a given month's
        // statement happened to render it.
        ProductIdentity plain = depositWith(new BigDecimal("5000"), LocalDate.of(2027, 3, 12));
        ProductIdentity scaled = depositWith(new BigDecimal("5000.00"), LocalDate.of(2027, 3, 12));

        assertThat(plain.matches(scaled)).isEqualTo(ProductIdentity.Match.EXACT);
    }

    @Test
    void adepositWithNoTermsAtAllFallsBackToNumberOnlyIdentity() {
        // Rather than hashing three nulls, which would make every attribute-less deposit identical
        // again -- the exact bug being fixed, reintroduced through the fallback.
        assertThat(ProductIdentity.forDeposit(null, null, null)).isNull();
    }

    @Test
    void aChangingInstallmentCountDoesNotChangeARecurringDepositsIdentity() {
        // installmentsPaid grows every month and is deliberately NOT part of the discriminator.
        // Including it would give the same RD a new identity in every statement, turning each
        // re-import into a new account.
        String may = ProductIdentity.forDeposit(null, LocalDate.of(2027, 5, 5), new BigDecimal("5000.00"));
        String june = ProductIdentity.forDeposit(null, LocalDate.of(2027, 5, 5), new BigDecimal("5000.00"));

        assertThat(may).isEqualTo(june);
    }

    @Test
    void theCompositeFixturesDepositsCarryDistinctAttributesForTheirIdentitiesToDeriveFrom() throws Exception {
        // The pipeline half of the contract. This fixture has no bank letterhead, so BankRegistry
        // resolves OTHER and every identity hash is legitimately null (an unrecognised institution
        // is not an institution -- see ProductIdentity.normalize). What it CAN prove is that the two
        // deposits reach staging with different terms, which is what the discriminator is built
        // from; the hashing itself is covered by the unit tests above.
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        List<StagedAccountSection> sections = new PdfPreviewGenerator(new PdfTextExtractor(),
                new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(categorizationService, new DuplicateDetector(mock(TransactionRepository.class)), com.finora.imports.TestRuleEngines.empty()),
                com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty())
                .generateSections(UUID.randomUUID(), "combined.pdf",
                        PdfFixtureBuilder.buildCompositeMultiProductStatementSample());

        List<String> discriminators = sections.stream()
                .filter(s -> "FIXED_DEPOSIT".equals(s.detectedAccount().detectedProduct()))
                .map(s -> ProductIdentity.forDeposit(s.detectedAccount().principalAmount(),
                        s.detectedAccount().maturityDate(), s.detectedAccount().installmentAmount()))
                .toList();

        assertThat(discriminators).hasSize(2);
        assertThat(discriminators.get(0)).isNotNull().isNotEqualTo(discriminators.get(1));
    }
}
