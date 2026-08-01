package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reusable diagnostic for debugging the PDF import pipeline against ANY real statement -- not
 * named after a bank, and never should be (see
 * docs/engineering/financial-document-intelligence-principles.md's "Diagnostics stay generic"
 * section, which this class exists to satisfy). Deliberately does NOT end in "Test" -- Surefire's
 * default include pattern (**&#47;*Test.java etc.) means this never runs as part of a normal
 * `mvn test`; run it explicitly with a real file path when debugging a new statement:
 *
 * <pre>
 *   mvn test -Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath=scratch-pdf/whatever.pdf
 * </pre>
 *
 * Reports what happened at every stage: which sections were found, how many rows survived table
 * location vs. normalization (and, for each one that didn't, which column made it fail), what
 * account-level metadata was detected vs. left null, and the final staged output -- everything
 * needed to find which layer a real-document bug actually belongs in, without hand-writing a new
 * one-off diagnostic (as this session's actual first draft, a since-deleted bank-named throwaway,
 * did) every time a new real PDF needs debugging.
 */
class PdfPipelineDiagnostic {

    public static void main(String[] args) throws Exception {
        String pathArg = args.length > 0 ? args[0] : System.getProperty("pdfPath");
        if (pathArg == null) {
            System.err.println("Usage: pass a PDF file path as args[0] or -DpdfPath=<path>");
            return;
        }
        new PdfPipelineDiagnostic().run(Path.of(pathArg));
    }

    // @Test-annotated JUnit entry point so `-Dtest=PdfPipelineDiagnostic#runFromSystemProperty`
    // actually has a real test method to invoke -- but this alone still can't make a bare
    // `mvn test` (no -Dtest filter) pick this class up, since Surefire's default include glob
    // (**&#47;*Test.java etc.) never matches this class's name in the first place. Skips itself
    // (not a failure) when pdfPath isn't set, so it's inert if anyone ever did run the full suite
    // with this class somehow in scope.
    @Test
    void runFromSystemProperty() throws Exception {
        String pathArg = System.getProperty("pdfPath");
        Assumptions.assumeTrue(pathArg != null, "Set -DpdfPath=<file> to run this diagnostic");
        run(Path.of(pathArg));
    }

    void run(Path pdfPath) throws Exception {
        byte[] bytes = Files.readAllBytes(pdfPath);
        System.out.println("=== Diagnosing: " + pdfPath + " (" + bytes.length + " bytes) ===\n");

        PdfTextExtractor textExtractor = new PdfTextExtractor();
        List<PositionedText> positioned = textExtractor.extract(bytes);
        System.out.println("Stage 1 -- Text extraction: " + positioned.size() + " positioned text runs");


        PdfTableLocator tableLocator = new PdfTableLocator();
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned);
        System.out.println("Stage 2 -- Table location: " + doc.sections().size() + " section(s) found\n");

        PdfMetadataExtractor metadataExtractor = new PdfMetadataExtractor();
        TransactionNormalizer transactionNormalizer = realTransactionNormalizer();

        for (int i = 0; i < doc.sections().size(); i++) {
            PdfTableLocator.LocatedSection section = doc.sections().get(i);
            System.out.println("--- Section " + i + " -----------------------------------------");
            System.out.println("  Raw bucketed rows: " + section.rows().size());
            System.out.println("  Auxiliary text lines: " + section.auxiliaryText().size());

            var metadata = metadataExtractor.extract(section.auxiliaryText());
            System.out.println("  Stage 3 -- Metadata: " + metadata);

            int survived = 0, dropped = 0;
            for (Map<String, String> row : section.rows()) {
                var normalized = transactionNormalizer.normalize(UUID.randomUUID(), row);
                if (normalized != null) {
                    survived++;
                } else {
                    dropped++;
                    System.out.println("  Stage 4 -- DROPPED (no valid date+amount): " + row);
                }
            }
            System.out.println("  Stage 4 -- Normalization: " + survived + " survived, " + dropped + " dropped\n");
        }

        PdfPreviewGenerator generator = new PdfPreviewGenerator(textExtractor, tableLocator, metadataExtractor, transactionNormalizer);
        List<StagedAccountSection> finalSections = generator.generateSections(UUID.randomUUID(), pdfPath.getFileName().toString(), bytes);
        System.out.println("=== Final staged output: " + finalSections.size() + " account section(s) ===");
        for (var s : finalSections) {
            System.out.println("  rows=" + s.rows().size() + " account=" + s.detectedAccount());
        }
    }

    private TransactionNormalizer realTransactionNormalizer() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorizationService, new DuplicateDetector(transactionRepository));
    }
}
