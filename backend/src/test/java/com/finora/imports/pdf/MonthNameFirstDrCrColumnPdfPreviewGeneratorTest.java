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
 * MONTH_NAME_FIRST_DATES / DR_CR_DIRECTION_COLUMN / BLOCK_PITCH_CONTINUATION, all three from one
 * real Bandhan Bank statement -- see {@code PdfFixtureBuilder.buildMonthNameFirstDrCrColumnSample}
 * for the layout and why the three travel together.
 *
 * <p>They are asserted in one place because they failed in one place, and in a revealing order.
 * The date format was fatal on its own: nothing parsed, so no row anchored, and the upload came
 * back as HTTP 422 "found a transaction table in this statement but could not read any
 * transactions from it" -- the visible failure. Fixing it exposed two quieter ones underneath, and
 * both produce a staged, plausible-looking import that is wrong: every credit signed as an expense,
 * and every description carrying a different transaction's last narration line. A test that stopped
 * at "the rows come back now" would have passed on both.
 */
class MonthNameFirstDrCrColumnPdfPreviewGeneratorTest {

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
        byte[] bytes = PdfFixtureBuilder.buildMonthNameFirstDrCrColumnSample();
        return realGenerator().generate(UUID.randomUUID(), "month_name_first_dr_cr.pdf", bytes);
    }

    private StagedRow rowDated(StagingResponse response, LocalDate date) {
        return response.rows().stream().filter(r -> date.equals(r.date())).findFirst().orElseThrow();
    }

    @Test
    void generate_anchorsEveryTransaction_onAMonthNameFirstDate() throws Exception {
        // The fatal one. "August22, 2026" parsed as nothing, so hasDateValue found no anchor
        // anywhere, every line fell into the leading-narration buffer, and the whole table
        // collapsed into a single unparseable row.
        StagingResponse response = generate();

        assertThat(response.rows()).hasSize(3);
        assertThat(response.rows()).extracting(StagedRow::date)
                .containsExactlyInAnyOrder(
                        LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 3));
    }

    @Test
    void generate_readsDirectionFromTheDrCrColumn_notTheDefault() throws Exception {
        // With "Dr / Cr" unrecognised there was no direction signal at all in this layout -- no
        // Type column, no separate Credit column, no marker on the amount -- so every row took the
        // final fallback and staged as an EXPENSE. Both credits are asserted, not just one: a
        // direction check that returns a constant passes any single-row test.
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 8, 22)).type()).isEqualTo("EXPENSE");
        assertThat(rowDated(response, LocalDate.of(2026, 8, 14)).type()).isEqualTo("INCOME");
        assertThat(rowDated(response, LocalDate.of(2026, 8, 3)).type()).isEqualTo("INCOME");
    }

    @Test
    void generate_keepsTheAmountSeparateFromItsCurrencyPrefixAndItsBalance() throws Exception {
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 8, 22)).amount()).isEqualByComparingTo("750.50");
        assertThat(rowDated(response, LocalDate.of(2026, 8, 22)).balanceAfter()).isEqualByComparingTo("7749.50");
        assertThat(rowDated(response, LocalDate.of(2026, 8, 14)).amount()).isEqualByComparingTo("1000.00");
        assertThat(rowDated(response, LocalDate.of(2026, 8, 3)).amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void generate_givesEachTransactionItsOwnThirdNarrationLine() throws Exception {
        // The quiet one. Each transaction's narration wraps onto three trailing lines, one past
        // MAX_TRAILING_CONTINUATION_ROWS, so the third was buffered forward and prepended to the
        // NEXT transaction: every description held a reference belonging to a different payment,
        // and the last transaction lost its own. Nothing was dropped and no amount changed, which
        // is why it needed looking for rather than reporting itself.
        StagingResponse response = generate();

        StagedRow debit = rowDated(response, LocalDate.of(2026, 8, 22));
        assertThat(debit.description())
                .contains("ABC000000000000000000000000000001")
                .doesNotContain("XYZ000000000000000000000000000002")
                .doesNotContain("XYZ000000000000000000000000000003");

        StagedRow middle = rowDated(response, LocalDate.of(2026, 8, 14));
        assertThat(middle.description())
                .contains("XYZ000000000000000000000000000002")
                .doesNotContain("ABC000000000000000000000000000001")
                .doesNotContain("XYZ000000000000000000000000000003");

        StagedRow oldest = rowDated(response, LocalDate.of(2026, 8, 3));
        assertThat(oldest.description())
                .contains("XYZ000000000000000000000000000003")
                .doesNotContain("XYZ000000000000000000000000000002");
    }

    @Test
    void generate_keepsTheWholeNarrationBlockWithItsTransaction() throws Exception {
        // Not only the third line: all three wrapped lines have to arrive on the transaction they
        // were printed under, in the order they were printed.
        StagingResponse response = generate();

        assertThat(rowDated(response, LocalDate.of(2026, 8, 22)).description())
                .isEqualTo("UPI/DR/D100000000001/ Generic Merchant/abc/ merchant@abc/UPI/ "
                        + "ABC000000000000000000000000000001");
    }
}
