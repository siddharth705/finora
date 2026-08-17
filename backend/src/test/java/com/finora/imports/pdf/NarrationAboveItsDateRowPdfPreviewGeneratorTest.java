package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
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
 * NARRATION_ABOVE_ITS_DATE_ROW: a statement that prints each transaction's narration on its own
 * line ABOVE the date row, with the wrapped remainder below it. See
 * {@code PdfFixtureBuilder.buildNarrationAboveItsDateRowSample} for the real Bank of Baroda layout
 * this is modeled on and the two bugs it exposed.
 *
 * <p>The blank-description symptom and the wrong-merchant symptom are asserted separately on
 * purpose, because the first fix alone causes the second. Making the description-column redirect
 * consult the table header brings the text back — and, without the proximity rule, brings it back
 * attached to the wrong transaction, which is worse than blank: the merchant is what categorisation
 * reads, so silently-wrong merchants are silently-wrong categories.
 */
class NarrationAboveItsDateRowPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector,
                com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildNarrationAboveItsDateRowSample();
        return realGenerator().generate(UUID.randomUUID(), "narration_above_date_row.pdf", bytes);
    }

    private StagedRow rowDated(StagingResponse response, LocalDate date) {
        return response.rows().stream().filter(r -> date.equals(r.date())).findFirst().orElseThrow();
    }

    @Test
    void generate_givesEveryTransactionADescription() throws Exception {
        // The reported symptom: a blank Description column on every row. The narration buckets into
        // the DATE column, the merge correctly refuses to append it onto a valid date, and the
        // redirect then had to find a description column in a row that has no NARRATION key yet.
        StagingResponse response = generate();

        assertThat(response.rows()).isNotEmpty();
        assertThat(response.rows()).allSatisfy(row ->
                assertThat(row.description()).as("description of the row dated %s", row.date()).isNotBlank());
    }

    @Test
    void generate_attachesEachNarrationToTheTransactionItIsPrintedAgainst() throws Exception {
        // The bug the first fix uncovers. Each narration head sits between two transactions; the
        // count cap gives it to the one above, and it belongs to the one below.
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 6, 2)).description())
                .contains("firstmerchant")
                .doesNotContain("secondmerchant")
                .doesNotContain("thirdmerchant");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 3)).description())
                .contains("secondmerchant")
                .doesNotContain("firstmerchant")
                .doesNotContain("thirdmerchant");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 4)).description())
                .contains("thirdmerchant")
                .doesNotContain("secondmerchant");
    }

    @Test
    void generate_keepsTheWrappedTailWithTheSameTransactionAsItsHead() throws Exception {
        // The head is above the date row and the tail is below it, so the two halves of one
        // narration are decided by opposite branches. They still have to land together.
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 6, 2)).description())
                .contains("UPI/100000000001/02:44:32/UPI/firstmerchant")
                .contains("one@bank");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 3)).description())
                .contains("two@bank").doesNotContain("one@bank");
    }

    /**
     * Updated for the marker-row pollution fix (docs/architecture/system-design/
     * marker-row-pollution-scope-investigation.md): the fixture's "Opening Balance" row now
     * classifies as RowKind.BALANCE_MARKER (no debit/credit column value -- see RowKind's own doc
     * comment) and is excluded from {@code response.rows()} entirely, so it is no longer possible
     * -- or necessary -- to look the row up there and check its description in isolation. What
     * this test originally protected against (the opening-balance row's OWN label getting
     * overwritten by the first transaction's narration, because it sits immediately above it) is
     * now protected more strongly: the row cannot surface a corrupted label to the user because it
     * cannot surface at all. {@code generate_attachesEachNarrationToTheTransactionItIsPrintedAgainst}
     * already pins "firstmerchant" onto the 06-02 transaction and nowhere else among the rows that
     * ARE visible, which is what remains to check once the marker row itself is out of scope.
     */
    @Test
    void generate_excludesTheOpeningBalanceRow_soItsLabelCanNeverBeCorruptedInTheReviewTable() throws Exception {
        StagingResponse response = generate();

        assertThat(response.rows()).noneMatch(r ->
                r.description() != null && r.description().contains("Opening Balance"));
        // The three real transactions -- and only them -- remain staged.
        assertThat(response.rows()).hasSize(3);
    }

    @Test
    void generate_readsTheAmountsFromTheirOwnColumns() throws Exception {
        // Proximity moves narration between transactions, so the guard that it never moves a row
        // carrying a figure is worth pinning: these amounts and their sign must survive it.
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 6, 2)).amount()).isEqualByComparingTo("1420.00");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 3)).amount()).isEqualByComparingTo("1211.00");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 4)).amount()).isEqualByComparingTo("750.00");
        assertThat(rowDated(response, LocalDate.of(2026, 6, 4)).type()).isEqualTo("EXPENSE");
    }
}
