package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The end-to-end proof for FD/RD attributes: one combined statement in, and the deposits come out
 * as real deposits -- their own principal, rate and maturity date -- rather than a name and a
 * balance sitting in the Investments module with nothing to distinguish them from a savings account.
 *
 * Also proves the two shapes are handled differently, which is the part that is easy to get wrong:
 * a fixed-deposit section's rows are separate DEPOSITS and split into one product each, while a
 * recurring deposit's rows are INSTALLMENTS of a single product and must never split (see
 * ProductAttributeExtractor's own doc comment -- splitting them would multiply one real deposit
 * into several phantom accounts).
 */
class DepositAttributeExtractionPdfPreviewGeneratorTest {

    private List<StagedAccountSection> stageComposite() throws Exception {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(mock(TransactionRepository.class)));

        PdfPreviewGenerator generator = new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), normalizer,
                com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator()));

        return generator.generateSections(UUID.randomUUID(), "combined.pdf",
                PdfFixtureBuilder.buildCompositeMultiProductStatementSample());
    }

    @Test
    void aFixedDepositSectionWithTwoRowsBecomesTwoDepositsEachWithItsOwnAttributes() throws Exception {
        // The real-world shape this exists for: an FD section lists every deposit the customer
        // holds, one row each. Treating the section as one product silently dropped the second.
        List<StagedAccountSection> sections = stageComposite();

        List<StagedAccountSection> fixedDeposits = sections.stream()
                .filter(s -> "FIXED_DEPOSIT".equals(s.detectedAccount().detectedProduct()))
                .toList();

        assertThat(fixedDeposits).as("two rows in the FD section are two different deposits").hasSize(2);

        assertThat(fixedDeposits.get(0).detectedAccount().principalAmount()).isEqualByComparingTo("100000.00");
        assertThat(fixedDeposits.get(0).detectedAccount().interestRate()).isEqualByComparingTo("7.10");
        assertThat(fixedDeposits.get(0).detectedAccount().maturityDate()).isEqualTo(LocalDate.of(2027, 3, 12));

        assertThat(fixedDeposits.get(1).detectedAccount().principalAmount()).isEqualByComparingTo("24053.00");
        assertThat(fixedDeposits.get(1).detectedAccount().interestRate()).isEqualByComparingTo("6.90");
        assertThat(fixedDeposits.get(1).detectedAccount().maturityDate()).isEqualTo(LocalDate.of(2027, 5, 1));
    }

    @Test
    void aRecurringDepositsInstallmentsStayOneProduct() throws Exception {
        List<StagedAccountSection> sections = stageComposite();

        List<StagedAccountSection> recurring = sections.stream()
                .filter(s -> "RECURRING_DEPOSIT".equals(s.detectedAccount().detectedProduct()))
                .toList();

        assertThat(recurring).as("installments are one product's payment history, not N accounts").hasSize(1);
        assertThat(recurring.get(0).detectedAccount().installmentsPaid()).isEqualTo(2);
        assertThat(recurring.get(0).detectedAccount().installmentAmount()).isEqualByComparingTo("5000.00");
        assertThat(recurring.get(0).detectedAccount().maturityDate()).isEqualTo(LocalDate.of(2027, 5, 5));
    }

    @Test
    void aDepositsRowsAreNeverStagedAsTransactions() throws Exception {
        // A fixed-deposit row has both a date-shaped and an amount-shaped column, which is exactly
        // what TransactionNormalizer looks for -- so before classification gated this, a deposit's
        // own principal and start date were fed in as a transaction candidate and either landed in
        // "unparseable" or were staged as a fabricated transaction against an Investments account.
        List<StagedAccountSection> sections = stageComposite();

        sections.stream()
                .filter(s -> !"SAVINGS".equals(s.detectedAccount().detectedProduct()))
                .forEach(deposit -> {
                    assertThat(deposit.rows()).as("a deposit has no transactions of its own").isEmpty();
                    assertThat(deposit.totalParsed()).isZero();
                    assertThat(deposit.unparseableRows())
                            .as("nor are its rows reported as failures -- they were never transactions")
                            .isEmpty();
                });
    }

    @Test
    void theSavingsLedgerIsUnaffectedAndStillStagesItsTransactions() throws Exception {
        // The regression guard: routing deposits away from transaction parsing must not touch the
        // ledger path that every other capability test depends on.
        List<StagedAccountSection> sections = stageComposite();

        StagedAccountSection savings = sections.stream()
                .filter(s -> "SAVINGS".equals(s.detectedAccount().detectedProduct()))
                .findFirst().orElseThrow();

        assertThat(savings.rows()).as("the savings section still parses its own ledger").hasSize(3);
        assertThat(savings.detectedAccount().principalAmount())
                .as("a ledger account has no deposit attributes").isNull();
    }
}
