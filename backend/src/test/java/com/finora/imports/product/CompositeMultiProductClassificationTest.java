package com.finora.imports.product;

import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.finora.imports.product.ProductEvidenceCollector.Section;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof for Financial Product Discovery: one document, three different products, each
 * classified as what it actually is.
 *
 * This is the test the capability exists to pass. Before it, every section of a combined statement
 * was offered to the user as an account to create -- including a term-deposit summary and an
 * installment schedule, neither of which is an account, and both of which then sat empty.
 */
class CompositeMultiProductClassificationTest {

    private final ProductEvidenceCollector collector = new ProductEvidenceCollector();
    private final FinancialProductClassifier classifier = new FinancialProductClassifier(collector);
    private final ProductValidator validator = new ProductValidator();
    private final ProductDiscovery discovery = new ProductDiscovery(collector, classifier, validator);

    private List<ProductDiscovery.DiscoveredProduct> discoverAll() throws IOException {
        byte[] pdf = PdfFixtureBuilder.buildCompositeMultiProductStatementSample();
        var doc = new PdfTableLocator().locateAll(new PdfTextExtractor().extract(pdf), null);

        List<ProductDiscovery.DiscoveredProduct> found = new ArrayList<>();
        for (int i = 0; i < doc.sections().size(); i++) {
            var s = doc.sections().get(i);
            found.add(discovery.discover(new Section(
                    s.rows().isEmpty() ? List.of() : List.copyOf(s.rows().get(0).keySet()),
                    s.auxiliaryText(), null, s.rows().size(), i, doc.sections().size())));
        }
        return found;
    }

    @Test
    void allThreeSectionsAreClassifiedAsThreeDifferentProducts() throws IOException {
        List<FinancialProductType> types = discoverAll().stream()
                .map(ProductDiscovery.DiscoveredProduct::type).toList();

        assertThat(types)
                .as("a savings ledger, a term deposit and a recurring deposit -- not three accounts")
                .containsExactly(FinancialProductType.SAVINGS, FinancialProductType.FIXED_DEPOSIT,
                        FinancialProductType.RECURRING_DEPOSIT);
    }

    @Test
    void theDepositsRouteToInvestmentsAndCarryNoTransactions() throws IOException {
        List<ProductDiscovery.DiscoveredProduct> found = discoverAll();

        assertThat(found.get(1).type().domain()).isEqualTo(FinancialProductType.Domain.INVESTMENT);
        assertThat(found.get(1).type().investmentKind()).isEqualTo("FD");
        assertThat(found.get(2).type().investmentKind()).isEqualTo("RD");
        assertThat(found.get(2).type().hasTransactions())
                .as("an RD's installments already appear on the savings account that funds it")
                .isFalse();
    }

    @Test
    void theRelationshipSummaryNamingThreeProductsCannotDecideAnySection() throws IOException {
        // The leak, as an assertion. "SAVINGS ACCOUNTS" is printed once at the top of the document,
        // in a summary that also names both deposit kinds. Naming three products at once is what
        // marks the block as describing the DOCUMENT: one section is one product, so an enumeration
        // cannot be a description of the section it happens to sit above.
        List<ProductDiscovery.DiscoveredProduct> found = discoverAll();

        for (ProductDiscovery.DiscoveredProduct product : found) {
            assertThat(product.evidence().productNamesAtLeast(EvidenceSource.SECTION_TEXT))
                    .as("no section may treat the document-level summary as its own naming")
                    .allMatch(f -> f.named() == product.type());
        }
    }

    @Test
    void theSavingsLedgerIsTheOnlySectionAllowedToCreateAnAccountAutomatically() throws IOException {
        List<ProductDiscovery.DiscoveredProduct> found = discoverAll();

        assertThat(found.get(0).mayCreateAutomatically())
                .as("a validated ledger is a real account")
                .isTrue();
        assertThat(found.get(0).type().accountType()).isEqualTo(com.finora.entity.Account.Type.SAVINGS);
    }

    @Test
    void everyProductCarriesPerSignalConfidenceNotOneBlendedNumber() throws IOException {
        var fd = discoverAll().get(1);

        List<FinancialProductClassifier.Evidence> positives = fd.classification().evidence().stream()
                .filter(e -> e.kind() == FinancialProductClassifier.EvidenceKind.POSITIVE).toList();

        assertThat(positives).as("the reasoning is per signal, so a wrong answer can be argued with")
                .isNotEmpty();
        assertThat(positives).allMatch(e -> e.confidence() > 0);
        assertThat(fd.classification().explain())
                .anyMatch(line -> line.contains("MATURITY_FIELD"));
    }

    @Test
    void aDepositScheduleContradictsSavingsRatherThanScoringSlightlyLowerAsOne() throws IOException {
        // The FD section carries a "Deposit(Mnth)" column -- the single word that used to make the
        // whole section read as a transaction account. Savings must be rejected here by
        // contradiction (a maturity field), not merely outscored.
        var fd = discoverAll().get(1);

        assertThat(fd.type()).isNotEqualTo(FinancialProductType.SAVINGS);
        assertThat(fd.evidence().looksLikeALedger())
                .as("no narration column means no ledger, whatever the amount columns are called")
                .isFalse();
    }
}
