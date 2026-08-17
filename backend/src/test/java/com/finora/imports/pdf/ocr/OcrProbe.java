package com.finora.imports.pdf.ocr;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.*;
import com.finora.imports.pdf.*;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real parser over runs that came from a recogniser and emits the same observation record
 * {@link com.finora.imports.analysis.SyntheticProbe} emits for a native document.
 *
 * <p><b>Deliberately the same shape.</b> The OCR-2B matcher compares an observation to a ground
 * truth without being told how the characters were obtained, and that is the point: if OCR output
 * needed its own record format, its own matcher or its own thresholds, the claim that OCR is an
 * acquisition strategy rather than a second parser would be false. One engine's scorecard is
 * therefore comparable to the native pipeline's on exactly the same axes.
 *
 * <p>The one added field is {@code engine}, which no matcher reads -- it is there so a scorecard can
 * say which row is which.
 */
final class OcrProbe {

    private OcrProbe() {}

    /**
     * @param runs the recognised text, already adapted. Passed in rather than extracted, which is
     *             the whole substitution: everything below this line is production code behaving
     *             exactly as it does for a native PDF.
     */
    static String probe(String engine, List<PositionedText> runs) throws IOException {
        var generated = generator(runs).generateSectionsWithContext(
                UUID.randomUUID(), "scanned-fixture.pdf", new byte[0], null);

        String sections = generated.sections().stream()
                .map(OcrProbe::section)
                .collect(Collectors.joining(","));

        return "{\"schema\":1,\"file\":\"scanned-fixture.pdf\",\"status\":\"ok\","
                + "\"engine\":" + quote(engine) + ","
                + "\"observed\":{"
                + "\"observationSource\":\"SYNTHETIC\","
                + "\"sections\":" + generated.sections().size() + ","
                + "\"sectionDetail\":[" + sections + "]}}";
    }

    /**
     * The production generator, with recognition substituted for extraction and nothing else.
     *
     * <p>Overriding the extractor rather than adding a seam to {@link PdfPreviewGenerator} keeps
     * OCR-3A to its stated scope. An evaluation that had to modify the parser in order to run would
     * already have made the routing decision it exists to inform.
     */
    /** The same wiring, driven by an acquirer -- used by the routing tests. */
    static PdfPreviewGenerator generatorFor(com.finora.imports.pdf.acquisition.DocumentTextAcquirer acquirer) {
        CategorizationService cat = mock(CategorizationService.class);
        when(cat.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new PdfPreviewGenerator(acquirer, new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(cat, new DuplicateDetector(repo), TestRuleEngines.empty()),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator()),
                TestRuleEngines.empty());
    }

    private static PdfPreviewGenerator generator(List<PositionedText> runs) {
        PdfTextExtractor recognised = new PdfTextExtractor() {
            @Override
            public List<PositionedText> extract(byte[] fileBytes) {
                return runs;
            }

            @Override
            public List<PositionedText> extract(byte[] fileBytes, String password) {
                return runs;
            }
        };

        CategorizationService cat = mock(CategorizationService.class);
        when(cat.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());

        return new PdfPreviewGenerator(recognised, new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(cat, new DuplicateDetector(repo), TestRuleEngines.empty()),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator()),
                TestRuleEngines.empty());
    }

    private static String section(StagedAccountSection s) {
        String type = s.detectedAccount() == null ? "UNKNOWN"
                : String.valueOf(s.detectedAccount().suggestedAccountType());
        String txns = s.rows().stream().map(OcrProbe::row).collect(Collectors.joining(","));
        return "{\"rows\":" + s.rows().size()
                + ",\"suggestedAccountType\":" + quote(type)
                + ",\"transactions\":[" + txns + "]}";
    }

    private static String row(StagedRow r) {
        return "{\"date\":" + quote(String.valueOf(r.date()))
                + ",\"amount\":" + quote(r.amount() == null ? "null" : r.amount().toPlainString())
                + ",\"direction\":" + quote("INCOME".equals(r.type()) ? "CREDIT" : "DEBIT")
                + ",\"currency\":\"INR\"}";
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
