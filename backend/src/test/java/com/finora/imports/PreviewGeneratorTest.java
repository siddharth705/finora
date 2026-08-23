package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Never lose information" (see docs/engineering/financial-document-intelligence-principles.md)
 * at the whole-file level: a CSV whose header row is never recognized used to come back with an
 * empty unparseableRows list, indistinguishable from a genuinely blank upload.
 */
class PreviewGeneratorTest {

    private PreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        return new PreviewGenerator(new CsvParser(), transactionNormalizer, new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()), com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void generate_surfacesEveryNonBlankLine_whenNoHeaderRowIsRecognizedAnywhere() throws Exception {
        String csv = "Some Institution\nThis file uses a layout the engine does not recognize\nfoo,bar,baz\n";
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "unrecognized.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.rows()).isEmpty();
        assertThat(response.unparseableRows()).hasSize(3);
        assertThat(response.unparseableRows()).allMatch(
                r -> r.reason().equals("No column header row was recognized anywhere in this file"));
        assertThat(response.unparseableRows()).anyMatch(r -> "Some Institution".equals(r.raw().get("column 1")));
    }

    // --- Third fix pass: a recognized column's unparseable value must never silently vanish -----
    //
    // See TransactionNormalizer.hasUnparseableRecognizedAmount's own doc comment. A row whose
    // Debit column holds a value CsvParser.parseNumeric cannot parse classifies BALANCE_MARKER
    // (its real amount column never resolves), and hasUnrecognizedNonBlankColumn (pass 2's guard)
    // finds nothing wrong -- every column NAME is recognized. Before this fix, such a row was
    // neither staged nor reported unparseable: it vanished with zero trace. End-to-end proof, at
    // the whole-file level, that it now lands in unparseableRows instead.

    @Test
    void generate_routesRowToUnparseable_whenARecognizedDebitColumnHoldsASlashDashSuffixedValue() throws Exception {
        // The exact row from the marker-row-pollution third-pass report.
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "02/07/2026,REAL RENT PAYMENT,1500/-,,48500.00\n";
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "slash-dash.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.rows()).isEmpty();
        assertThat(response.unparseableRows()).hasSize(1);
        assertThat(response.unparseableRows().get(0).reason())
                .contains("recognized transactional amount column that could not be parsed");
        assertThat(response.unparseableRows().get(0).raw().get("Debit")).isEqualTo("1500/-");
    }

    @Test
    void generate_routesRowToUnparseable_whenARecognizedColumnHoldsAMalformedDrCrStyleValue() throws Exception {
        // Confirms the fix generalizes rather than pattern-matching the literal "1500/-" string:
        // a differently-shaped unparseable value in the same recognized Debit column, evoking one
        // of the other real bank formats CsvParser.parseNumeric's own javadoc documents historically
        // choking on with this exact "row vanishes" failure mode (Union Bank's parenthesized Cr/Dr
        // suffix) -- here corrupted just enough (a stray leading letter) that it still fails to
        // parse today.
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "03/07/2026,REAL DEBIT,X50000.00(Cr),,48500.00\n";
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "malformed-drcr.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.rows()).isEmpty();
        assertThat(response.unparseableRows()).hasSize(1);
        assertThat(response.unparseableRows().get(0).reason())
                .contains("recognized transactional amount column that could not be parsed");
    }
}
