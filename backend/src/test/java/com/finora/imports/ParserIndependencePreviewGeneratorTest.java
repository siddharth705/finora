package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
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
 * "The parser should change. The normalized output should not." (refined test-corpus strategy,
 * docs/engineering/financial-document-intelligence-principles.md.) The concrete, buildable way to
 * verify this today -- CsvParser/PdfTableLocator both feed the exact same {@link
 * TransactionNormalizer#normalize} once they've produced a header-keyed row map, so the same
 * logical transaction data fed through the CSV path and the PDF path must normalize identically.
 * This does NOT test Excel/OFX/QFX/CAMT.053/MT940 -- those parsers don't exist yet; the principle
 * is proven against the two parsers that do, not asserted speculatively for ones that don't.
 */
class ParserIndependencePreviewGeneratorTest {

    private PreviewGenerator csvGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        return new PreviewGenerator(new CsvParser(), transactionNormalizer, new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator()), com.finora.imports.TestRuleEngines.empty());
    }

    private PdfPreviewGenerator pdfGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void sameLogicalStatementData_normalizesIdentically_regardlessOfWhetherItArrivedAsCsvOrPdf() throws Exception {
        // Same headers, same three rows as PdfFixtureBuilder.buildReferenceNumberAndBalanceSample
        // (a shape originally evidenced by a real Canara Bank statement -- see that method's own
        // doc comment, including its data-hygiene note), just expressed as CSV text instead of a
        // rendered PDF table.
        String csv = "Date,Particulars,Reference No,Amount,Balance\n"
                + "01/07/2026,UPI/DR/234567890123/GENERIC MERCHANT,234567890123,-1000.00,49000.00\n"
                + "01/07/2026,MOB-IMPS/CR/RAHUL VERMA,10203040506070,1000.00,50000.00\n"
                + "02/07/2026,UPI/DR/345678901234/GENERIC PAYEE,345678901234,-150.00,49850.00\n";

        UUID userId = UUID.randomUUID();
        List<StagedRow> csvRows = csvGenerator()
                .generate(userId, "statement.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))
                .rows();
        List<StagedRow> pdfRows = pdfGenerator()
                .generate(userId, "statement.pdf", PdfFixtureBuilder.buildReferenceNumberAndBalanceSample())
                .rows();

        assertThat(csvRows).hasSize(3);
        assertThat(pdfRows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            StagedRow csvRow = csvRows.get(i);
            StagedRow pdfRow = pdfRows.get(i);
            assertThat(csvRow.date()).as("row %d date", i).isEqualTo(pdfRow.date());
            assertThat(csvRow.description()).as("row %d description", i).isEqualTo(pdfRow.description());
            assertThat(csvRow.amount()).as("row %d amount", i).isEqualByComparingTo(pdfRow.amount());
            assertThat(csvRow.type()).as("row %d type", i).isEqualTo(pdfRow.type());
            assertThat(csvRow.referenceNumber()).as("row %d referenceNumber", i).isEqualTo(pdfRow.referenceNumber());
            assertThat(csvRow.balanceAfter()).as("row %d balanceAfter", i).isEqualByComparingTo(pdfRow.balanceAfter());
        }
    }

    @Test
    void sameLogicalStatementData_producesTheSameLayoutFingerprintShape_regardlessOfParser() throws Exception {
        // Deliberately NOT asserting the two fingerprints are EQUAL -- sourceFormat ("CSV" vs
        // "PDF") is itself part of the fingerprint input by design (see DocumentContext's own doc
        // comment: a CSV and a PDF sharing the same header set are still different source
        // documents), so this only proves both parsers produce a well-formed, versioned ID.
        String csv = "Date,Particulars,Reference No,Amount,Balance\n"
                + "01/07/2026,UPI/DR/234567890123/GENERIC MERCHANT,234567890123,-1000.00,49000.00\n";
        UUID userId = UUID.randomUUID();

        var csvResult = csvGenerator().generateWithContext(userId, "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        var pdfResult = pdfGenerator().generateSectionsWithContext(userId, "statement.pdf",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        assertThat(csvResult.documentContext().buildFingerprint()).matches("FP-1-[0-9A-F]{8}");
        assertThat(pdfResult.documentContext().buildFingerprint()).matches("FP-1-[0-9A-F]{8}");
    }
}
