package com.finora.imports.analysis;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.*;
import com.finora.imports.pdf.*;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A corpus record for a SYNTHETIC document, carrying the financial values a real one may not.
 *
 * <h2>Why this is a separate probe and not a flag on CorpusProbe</h2>
 *
 * {@link CorpusProbe} runs over real bank statements. Its record carries counts, enumerations and
 * classifications and deliberately no amounts, dates or narration, for the same reason
 * {@code ImportVerificationRecorder}'s allowlist admits counts and refuses money: those files become
 * build artefacts, and a real customer's transactions must not.
 *
 * <p>Value-level ground truth needs the observed side to carry values, so it gets its own probe that
 * only ever sees generated documents. The separation is what keeps the privacy boundary from moving
 * to make the matcher more capable.
 *
 * <p>Every record declares {@code observationSource: SYNTHETIC}. The matcher refuses financial values
 * on any other source, so the boundary is structural rather than dependent on nobody adding a field
 * to the real probe later.
 */
public final class SyntheticProbe {

    private SyntheticProbe() {}

    private static PdfPreviewGenerator generator() {
        CategorizationService cat = mock(CategorizationService.class);
        when(cat.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(cat.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                new TransactionNormalizer(cat, new DuplicateDetector(repo), TestRuleEngines.empty()),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator()),
                TestRuleEngines.empty());
    }

    public static String probe(Path pdf) throws Exception {
        var generated = generator().generateSectionsWithContext(
                UUID.randomUUID(), pdf.getFileName().toString(), Files.readAllBytes(pdf), null);

        String sections = generated.sections().stream()
                .map(SyntheticProbe::section)
                .collect(Collectors.joining(","));

        return "{\"schema\":1,\"file\":" + quote(pdf.getFileName().toString())
                + ",\"status\":\"ok\",\"observed\":{"
                + "\"observationSource\":\"SYNTHETIC\","
                + "\"sections\":" + generated.sections().size() + ","
                + "\"sectionDetail\":[" + sections + "]}}";
    }

    private static String section(StagedAccountSection s) {
        String type = s.detectedAccount() == null ? "UNKNOWN"
                : String.valueOf(s.detectedAccount().suggestedAccountType());
        String txns = s.rows().stream().map(SyntheticProbe::row).collect(Collectors.joining(","));
        return "{\"rows\":" + s.rows().size()
                + ",\"suggestedAccountType\":" + quote(type)
                + ",\"transactions\":[" + txns + "]}";
    }

    /** Direction as DEBIT/CREDIT rather than a sign, matching what the ground truth asserts and what
     *  a misreading actually flips. */
    private static String row(StagedRow r) {
        return "{\"date\":" + quote(String.valueOf(r.date()))
                + ",\"amount\":" + quote(r.amount() == null ? "null" : r.amount().toPlainString())
                + ",\"direction\":" + quote("INCOME".equals(r.type()) ? "CREDIT" : "DEBIT")
                + ",\"currency\":\"INR\"}";
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: SyntheticProbe <synthetic.pdf>");
            System.exit(2);
        }
        System.out.println(probe(Path.of(args[0])));
    }
}
