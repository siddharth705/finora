package com.finora.imports.analysis;

import com.finora.imports.*;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.pdf.*;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs one statement through the import pipeline and prints exactly one JSON object describing what
 * happened. Read-only: it constructs the same pipeline the diagnostic does and changes nothing.
 *
 * <h2>Why this is separate from PdfPipelineDiagnostic</h2>
 *
 * That class is a human-readable report, and {@code scripts/trace-capture.sh} invokes one of its test
 * methods by name. Widening its output format to serve a machine consumer would put the trace-capture
 * workflow at risk for no benefit. This class exists to be parsed; that one exists to be read.
 *
 * <h2>Observed facts and derived signals are kept apart</h2>
 *
 * The JSON has two sibling objects, {@code observed} and {@code derived}, and the split is the point.
 * A derived value is a current opinion: {@code documentClassification} has already been wrong twice
 * during this milestone, once because image density was mistaken for text density and once because
 * positioned-run count was mistaken for text presence. When the next opinion turns out to be wrong,
 * the raw measurements have to still be there to re-derive from. So nothing observed is ever replaced
 * by something derived, and a consumer can ignore {@code derived} entirely.
 *
 * <h2>rowsPerPage is deliberately absent</h2>
 *
 * {@code rows} and {@code pages} are both present and a consumer can form whatever ratio it wants at
 * full precision. Emitting the integer-divided value would invite comparing a lossy number across
 * runs -- HSBC's one row over four pages is {@code 0}, and so is a regression from twelve rows to
 * three.
 */
public final class CorpusProbe {

    private static final int SCHEMA = 1;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CorpusProbe <path-to.pdf>");
            System.exit(2);
        }
        Path pdf = Path.of(args[0]);
        // One statement must never take the corpus run down with it, so every failure mode becomes a
        // record rather than a stack trace. Throwable, not Exception: a malformed PDF can surface as
        // an Error from a decoder, and a corpus that stops at file 3 of 16 is not a corpus.
        try {
            System.out.println(probe(pdf));
        } catch (Throwable t) {
            System.out.println(errorRecord(pdf, t));
        }
    }

    static String probe(Path pdf) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);

        int pages;
        int extractedChars;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            pages = doc.getNumberOfPages();
            // A plain text strip, NOT the pipeline's positioned extraction. This is the only signal
            // that says whether the document contains readable text at all; the positioned-run count
            // below measures the pipeline instead, which is why both are recorded.
            extractedChars = new PDFTextStripper().getText(doc).replaceAll("\\s", "").length();
        }

        PdfTextExtractor textExtractor = new PdfTextExtractor();
        PdfTableLocator tableLocator = new PdfTableLocator();
        int positionedRuns = textExtractor.extract(bytes).size();

        // Constructed exactly as PdfPipelineDiagnostic does, so this probe cannot drift into
        // exercising a different pipeline than the one the human-readable report describes.
        PdfPreviewGenerator generator = new PdfPreviewGenerator(
                textExtractor, tableLocator, new PdfMetadataExtractor(), stubbedNormalizer(),
                ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator()),
                TestRuleEngines.empty());

        var generated = generator.generateSectionsWithContext(
                UUID.randomUUID(), pdf.getFileName().toString(), bytes, null);

        List<StagedAccountSection> sections = generated.sections();
        int rows = 0;
        List<String> banks = new ArrayList<>();
        Map<String, String> verification = new LinkedHashMap<>();
        for (StagedAccountSection section : sections) {
            rows += section.rows().size();
            var account = section.detectedAccount();
            if (account != null && account.bank() != null) banks.add(account.bank().id());
            var report = section.verification();
            if (report != null) {
                for (var finding : report.findings()) {
                    // WORST outcome across sections wins, never the last one seen.
                    //
                    // "Last wins" was the first version and it hid a real finding: Shivani_HDFC has
                    // three sections with a COLUMN_AMBIGUITY WARNING on one of them, and a later
                    // section's VERIFIED overwrote it -- the record then classified the document
                    // PARSED_COMPLETE. A multi-section statement is exactly where a per-section
                    // problem is easiest to lose, and losing it here would let a regression on one
                    // section of a composite statement pass a diff unnoticed.
                    verification.merge(finding.rule(), finding.outcome(), CorpusProbe::worse);
                }
            }
        }

        String fingerprint = generated.documentContext() == null ? "unknown"
                : generated.documentContext().buildFingerprint();
        List<String> capabilities = new ArrayList<>();
        if (generated.documentContext() != null) {
            // "NAME:STATUS", not the record's toString. A diff needs to see a capability appear,
            // disappear or change status; it must not report a change because a field was added to
            // CapabilityActivation.
            generated.documentContext().capabilities()
                    .forEach(c -> capabilities.add(c.capability() + ":" + c.status()));
        }

        // expectedTransactions is null everywhere today. Ground truth is a later step, and inferring
        // it from `rows` would make the current parser the definition of correct.
        var signals = new DocumentClassification.Signals(
                pages, extractedChars, positionedRuns, sections.size(), rows, verification, null);

        StringBuilder j = new StringBuilder();
        j.append('{')
         .append("\"schema\":").append(SCHEMA)
         .append(",\"file\":").append(quote(pdf.getFileName().toString()))
         .append(",\"status\":\"ok\"")
         .append(",\"observed\":{")
         .append("\"pages\":").append(pages)
         .append(",\"extractedChars\":").append(extractedChars)
         .append(",\"positionedRuns\":").append(positionedRuns)
         .append(",\"sections\":").append(sections.size())
         .append(",\"rows\":").append(rows)
         .append(",\"layoutFingerprint\":").append(quote(fingerprint))
         .append(",\"banks\":").append(stringArray(banks))
         .append(",\"capabilities\":").append(stringArray(capabilities))
         .append(",\"verification\":").append(stringMap(verification))
         .append('}')
         .append(",\"derived\":{")
         .append("\"documentClassification\":\"").append(DocumentClassification.of(signals)).append('"')
         .append(",\"suspectedIncompleteByPageRatio\":").append(signals.suspectedIncompleteByPageRatio())
         .append('}')
         .append('}');
        return j.toString();
    }

    /**
     * The normalizer with its two collaborators stubbed, mirroring
     * {@code PdfPipelineDiagnostic.realTransactionNormalizer()}.
     *
     * <p>Categorisation and duplicate detection are database-backed and irrelevant to what this probe
     * measures -- extraction shape, not what a category or a duplicate flag ends up being. Stubbing
     * them keeps the probe runnable from a plain `java` invocation with no Spring context and no
     * Postgres, which is what makes a 16-statement sweep cheap enough to run after every change.
     */
    private static TransactionNormalizer stubbedNormalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions),
                TestRuleEngines.empty());
    }

    /** Severity ranking used to collapse per-section outcomes without discarding the worst one. */
    static int severity(String outcome) {
        return switch (outcome) {
            case "FAILED" -> 3;
            case "WARNING" -> 2;
            case "VERIFIED" -> 1;
            default -> 0;                 // NOT_APPLICABLE and anything unrecognised
        };
    }

    static String worse(String a, String b) {
        return severity(b) > severity(a) ? b : a;
    }

    static String errorRecord(Path pdf, Throwable t) {
        return "{\"schema\":" + SCHEMA
                + ",\"file\":" + quote(pdf.getFileName().toString())
                + ",\"status\":\"error\""
                + ",\"error\":{\"type\":" + quote(t.getClass().getSimpleName())
                + ",\"message\":" + quote(String.valueOf(t.getMessage())) + "}}";
    }

    private static String stringArray(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    private static String stringMap(Map<String, String> map) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** Minimal JSON string escaping. Statement filenames contain spaces, dots and apostrophes. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private CorpusProbe() {}
}
