package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.CsvParser;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.imports.pdf.fixtures.PdfTraceRedactor;
import com.finora.imports.pdf.fixtures.TraceMetadata;
import com.finora.imports.pdf.fixtures.TraceQualityReport;
import com.finora.imports.pdf.fixtures.TraceValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reusable diagnostic for debugging the PDF import pipeline against ANY real statement -- not
 * named after a bank, and never should be (see
 * docs/engineering/financial-document-intelligence-principles.md's "Diagnostics stay generic"
 * section, which this class exists to satisfy, and Phase 4's "every engineer should be able to
 * upload any document and immediately see..." goal, which this class is the concrete answer to).
 * Deliberately does NOT end in "Test" -- Surefire's default include pattern (**&#47;*Test.java
 * etc.) means this never runs as part of a normal `mvn test`; run it explicitly with a real file
 * path when debugging a new statement:
 *
 * <pre>
 *   mvn test -Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath=scratch-pdf/whatever.pdf
 * </pre>
 *
 * Reports, per section: detected capabilities, full auxiliary text (for spotting a metadata
 * pattern the extractor doesn't handle yet), detected metadata vs. what's still null, and for
 * every row that failed to normalize, a specific reason -- not just the raw row -- so the
 * diagnostic answers "why" a row was dropped, not just "that" it was. This is the primary
 * debugging tool for a new real document; it replaces hand-written one-off diagnostics entirely.
 *
 * "Parser confidence" (per-field, not per-document) is intentionally NOT reported here -- it
 * doesn't exist as a concept in the pipeline yet (see the engineering principles doc's Phase 3).
 * Reporting a fabricated confidence number would be worse than reporting none.
 */
class PdfPipelineDiagnostic {

    // Loose, diagnostic-only checks for which known capabilities a document appears to exercise --
    // deliberately separate from (and simpler than) the actual parsing logic in CsvParser/
    // PdfTableLocator, since this only needs to report a signal, not act on it. Not exhaustive:
    // some capabilities (REPEATED_HEADER skips, which grid-fallback path metadata came from)
    // leave no trace in the final data to detect after the fact without deeper instrumentation --
    // omitted rather than faked.
    private static final Pattern PARENTHESIZED_DR_CR = Pattern.compile("(?i)\\(\\s*(dr|cr)\\.?\\s*\\)");
    private static final Pattern BARE_DR_CR = Pattern.compile("(?i)(?<!\\()\\s(dr|cr)\\.?\\s*$");

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

    /**
     * Turns a real statement into a committable regression fixture:
     *
     * <pre>
     *   mvn test -Dtest=PdfPipelineDiagnostic#captureRedactedTrace \
     *            -DpdfPath=scratch-pdf/whatever.pdf -DtraceName=hdfc-txn-date-header
     * </pre>
     *
     * Writes {@code src/test/resources/traces/&lt;traceName&gt;.trace} -- the document's text layer
     * with coordinates intact and every non-structural token masked (see {@link PdfTraceRedactor}).
     * This is the step that makes "every production bug becomes a permanent regression case"
     * affordable: it takes one command rather than an afternoon of hand-authoring a synthetic PDF
     * that approximates the layout and usually fails to reproduce the bug.
     *
     * The trace is VALIDATED before it is written (see {@link TraceValidator}): a capture that
     * still contains unmasked customer data, or that lost the structural evidence it was captured
     * to preserve, is refused rather than written for someone to notice later. "Read the file
     * before committing" was the previous control, and a customer's name and account number
     * reached the repository under it.
     *
     * Provenance is recorded into the file itself -- which redactor and which allowlist produced
     * it, what it protects, and why it exists -- so a later change to either can identify the
     * traces it invalidated. See {@code docs/engineering/trace-lifecycle.md}.
     */
    @Test
    void captureRedactedTrace() throws Exception {
        String pathArg = System.getProperty("pdfPath");
        String traceName = System.getProperty("traceName");
        Assumptions.assumeTrue(pathArg != null && traceName != null,
                "Set -DpdfPath=<file> -DtraceName=<name> to capture a trace fixture");

        List<PositionedText> positioned = new PdfTextExtractor().extract(Files.readAllBytes(Path.of(pathArg)));
        List<PositionedText> redacted = PdfTraceRedactor.redact(positioned);

        TraceMetadata metadata = new TraceMetadata(
                TraceMetadata.CURRENT_TRACE_VERSION,
                PdfTraceRedactor.REDACTOR_VERSION,
                PdfTraceRedactor.allowlistFingerprint(),
                java.time.LocalDate.now().toString(),
                System.getProperty("source", "unspecified"),
                csvProperty("capabilities"),
                csvProperty("regressions"),
                System.getProperty("motivation", ""),
                csvProperty("requiredHeaders"));

        String content = PdfTrace.format(redacted, metadata);
        TraceValidator.Result result = TraceValidator.validate(traceName, content);

        System.out.println();
        System.out.println(TraceQualityReport.render(result));

        if (!result.isCommittable()) {
            // Deliberately not written. A refused capture that still lands on disk is one `git add`
            // away from being committed by someone who did not read this output.
            throw new AssertionError("Trace REFUSED -- not written. "
                    + result.blockers().size() + " blocker(s) above must be resolved first.");
        }

        Path out = Path.of("src/test/resources/traces", traceName + ".trace");
        Files.createDirectories(out.getParent());
        Files.writeString(out, content);
        System.out.println("Written -> " + out.toAbsolutePath());
    }

    private static java.util.List<String> csvProperty(String name) {
        String raw = System.getProperty(name, "");
        if (raw.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    void run(Path pdfPath) throws Exception {
        byte[] bytes = Files.readAllBytes(pdfPath);
        System.out.println("=== Diagnosing: " + pdfPath + " (" + bytes.length + " bytes) ===\n");

        PdfTextExtractor textExtractor = new PdfTextExtractor();
        List<PositionedText> positioned = textExtractor.extract(bytes);
        System.out.println("Stage 1 -- Text extraction: " + positioned.size() + " positioned text runs");

        PdfTableLocator tableLocator = new PdfTableLocator();
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned);
        System.out.println("Stage 2 -- Table location: " + doc.sections().size() + " section(s) found");
        if (doc.sections().size() > 1) {
            System.out.println("  [CAPABILITY] COMPOSITE_STATEMENT / MULTI_ACCOUNT -- more than one section detected");
        }
        if (doc.sections().isEmpty()) {
            reportHeaderDetectionFailure(tableLocator, positioned);
        }
        System.out.println();

        PdfMetadataExtractor metadataExtractor = new PdfMetadataExtractor();
        TransactionNormalizer transactionNormalizer = realTransactionNormalizer();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < doc.sections().size(); i++) {
            PdfTableLocator.LocatedSection section = doc.sections().get(i);
            System.out.println("--- Section " + i + " -----------------------------------------");
            System.out.println("  Raw bucketed rows: " + section.rows().size());
            System.out.println("  Detected table columns: " + detectedColumns(section));
            System.out.println("  Auxiliary text (" + section.auxiliaryText().size() + " lines):");
            section.auxiliaryText().forEach(line -> System.out.println("    | " + line));

            var metadata = metadataExtractor.extract(section.auxiliaryText());
            System.out.println("  Stage 3 -- Metadata: " + metadata);
            reportNullMetadataAsWarnings(metadata, i, warnings);

            System.out.println("  Capabilities observed in this section:");
            for (String capability : detectCapabilities(section)) {
                System.out.println("    [CAPABILITY] " + capability);
            }

            Set<String> unknown = unrecognizedColumns(section);
            if (!unknown.isEmpty()) {
                // Not a failure, and not necessarily even a problem -- a column TransactionNormalizer
                // never looks at (a merchant-category column, an instrument ID, a NeuCoins balance)
                // is exactly the kind of thing "Never lose information" exists to surface: today it's
                // just unused, but it's also the raw material tomorrow's capability might come from.
                // See the engineering principles doc's "Capability lifecycle."
                System.out.println("  [UNKNOWN FIELDS] Present in the data, not used by any known capability: " + unknown);
            }

            // No per-field confidence exists yet -- see Phase 3 of the engineering principles doc.
            // Stated explicitly so its absence here reads as a deliberate, documented gap, not an
            // oversight in this diagnostic.
            System.out.println("  Confidence: not yet implemented (see Phase 3 -- \"Collect Knowledge\")");

            int survived = 0, dropped = 0;
            for (Map<String, String> row : section.rows()) {
                var normalized = transactionNormalizer.normalize(UUID.randomUUID(), row);
                if (normalized != null) {
                    survived++;
                } else {
                    dropped++;
                    System.out.println("  Stage 4 -- DROPPED: " + transactionNormalizer.explainFailure(row) + " -- row=" + row);
                }
            }
            System.out.println("  Stage 4 -- Normalization: " + survived + " survived, " + dropped + " dropped");
            if (section.rows().isEmpty()) {
                warnings.add("Section " + i + ": zero rows detected at all -- table location likely failed, not just normalization");
            } else if (survived == 0) {
                warnings.add("Section " + i + ": every row was dropped -- check the reasons above before assuming this file has no transactions");
            }
            System.out.println();
        }

        if (!warnings.isEmpty()) {
            System.out.println("=== Warnings ===");
            warnings.forEach(w -> System.out.println("  ! " + w));
            System.out.println();
        }

        PdfPreviewGenerator generator = new PdfPreviewGenerator(textExtractor, tableLocator, metadataExtractor, transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.BalanceChainValidator());
        List<StagedAccountSection> finalSections = generator.generateSections(UUID.randomUUID(), pdfPath.getFileName().toString(), bytes);
        System.out.println("=== Final staged output: " + finalSections.size() + " account section(s) ===");
        for (var s : finalSections) {
            System.out.println("  rows=" + s.rows().size() + " account=" + s.detectedAccount());
        }
    }

    /**
     * Bug fix: when table location found NOTHING, this diagnostic used to print a bare
     * "0 section(s) found" and then skip the entire per-section loop -- going completely silent in
     * the exact case an engineer most needs it (a document that imported "successfully" with zero
     * transactions). Two real statements hit this. Now it dumps the reconstructed lines and, for
     * each, scores it against the two conditions {@code looksLikeHeaderRow} actually requires
     * (a cell normalizing to "date", plus >= 2 recognized header names), so the near-miss line is
     * visible immediately rather than needing a separate one-off probe to find.
     */
    private void reportHeaderDetectionFailure(PdfTableLocator tableLocator, List<PositionedText> positioned) {
        // locate() (single-table wrapper) returns every reconstructed line as preTableLines when no
        // header was ever recognized -- exactly the "what did the parser actually see" view needed
        // here, without duplicating PdfTableLocator's private line-grouping logic.
        List<String> lines = tableLocator.locate(positioned).preTableLines();
        System.out.println("  [HEADER DETECTION FAILED] No row satisfied: a cell normalizing to \"date\""
                + " AND >= 2 cells matching known header names.");
        System.out.println("  Reconstructed lines (" + lines.size() + "), scored as header candidates:");
        List<String> nearMisses = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hits = 0;
            boolean hasDate = false;
            for (String cell : line.split("\\s{2,}|\\t")) {
                String normalized = CsvParser.normalizeHeaderCell(cell);
                if (TransactionNormalizer.recognizedColumnNames().contains(normalized.toLowerCase())) hits++;
                if (normalized.equals("date") || normalized.equals("date & time")) hasDate = true;
            }
            if (hits > 0 || hasDate) {
                nearMisses.add("    line " + i + " [hints=" + hits + " date=" + hasDate + "] " + line);
            }
        }
        if (nearMisses.isEmpty()) {
            System.out.println("    (no line contained even ONE recognized column name -- this is likely a"
                    + " scanned/image-only PDF with no text layer, or a layout with no tabular header at all)");
        } else {
            System.out.println("    Candidate lines containing at least one recognized column name:");
            nearMisses.forEach(System.out::println);
        }
        int dumpCap = Math.min(lines.size(), 60);
        System.out.println("    First " + dumpCap + " raw lines:");
        for (int i = 0; i < dumpCap; i++) System.out.println("      | " + lines.get(i));
    }

    private void reportNullMetadataAsWarnings(PdfMetadataExtractor.ExtractedMetadata metadata, int sectionIndex, List<String> warnings) {
        if (metadata.accountHolderName() == null && metadata.accountNumberMasked() == null && metadata.ifscCode() == null) {
            warnings.add("Section " + sectionIndex + ": account holder, account number, AND IFSC all null -- " +
                    "likely a metadata layout GRID_METADATA_FALLBACK doesn't handle yet, not just missing data");
        }
    }

    // Union across all rows, not just the first -- guards against the (rare, but not impossible)
    // case where a section's rows don't all share identical key sets.
    private Set<String> detectedColumns(PdfTableLocator.LocatedSection section) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, String> row : section.rows()) columns.addAll(row.keySet());
        return columns;
    }

    // Compares on CsvParser.normalizeHeaderCell(column) rather than the raw column text: row keys
    // in a LocatedSection are the header text exactly as extracted ("DATE", "AMOUNT (Rs.)"), not
    // pre-normalized, so a raw string-equality check against TransactionNormalizer's normalized
    // hint names would falsely flag every recognized column as unknown.
    private Set<String> unrecognizedColumns(PdfTableLocator.LocatedSection section) {
        Set<String> recognized = TransactionNormalizer.recognizedColumnNames();
        Set<String> unknown = new LinkedHashSet<>();
        for (String column : detectedColumns(section)) {
            if (!recognized.contains(CsvParser.normalizeHeaderCell(column).toLowerCase())) {
                unknown.add(column);
            }
        }
        return unknown;
    }

    private List<String> detectCapabilities(PdfTableLocator.LocatedSection section) {
        List<String> found = new ArrayList<>();
        boolean hasParenthesizedDrCr = false, hasBareDrCr = false, hasLeadingPlus = false, hasBalance = false;
        for (Map<String, String> row : section.rows()) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                String value = e.getValue();
                if (value == null) continue;
                if (PARENTHESIZED_DR_CR.matcher(value).find()) hasParenthesizedDrCr = true;
                if (BARE_DR_CR.matcher(value).find()) hasBareDrCr = true;
                if (value.trim().startsWith("+")) hasLeadingPlus = true;
                if (CsvParser.normalizeHeaderCell(e.getKey()).contains("balance") && !value.isBlank()) hasBalance = true;
            }
        }
        if (hasParenthesizedDrCr) found.add("DR_CR_SUFFIX (parenthesized, e.g. \"(Cr)\")");
        if (hasBareDrCr) found.add("DR_CR_SUFFIX (bare, e.g. \" Dr\")");
        if (hasLeadingPlus) found.add("LEADING_PLUS_CREDIT");
        if (hasBalance) found.add("RUNNING_BALANCE");
        boolean hasCreditCardSignal = section.auxiliaryText().stream().anyMatch(line -> {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("total payment due") || lower.contains("minimum amount due")
                    || lower.contains("minimum due") || lower.contains("credit limit") || lower.contains("card number");
        });
        if (hasCreditCardSignal) found.add("CREDIT_CARD_SUMMARY_SIGNAL");
        return found;
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
