package com.finora.imports.analysis;

import com.finora.imports.TestAccountRepositories;

import com.finora.imports.*;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.pdf.*;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
        boolean synthetic = false;
        String pdfArg = null;
        for (String arg : args) {
            if ("--synthetic".equals(arg)) {
                synthetic = true;
            } else {
                pdfArg = arg;
            }
        }
        if (pdfArg == null) {
            System.err.println("Usage: CorpusProbe [--synthetic] <path-to.pdf>");
            System.exit(2);
            return;
        }
        Path pdf = Path.of(pdfArg);
        // One statement must never take the corpus run down with it, so every failure mode becomes a
        // record rather than a stack trace. Throwable, not Exception: a malformed PDF can surface as
        // an Error from a decoder, and a corpus that stops at file 3 of 16 is not a corpus.
        try {
            System.out.println(probe(pdf, synthetic));
        } catch (Throwable t) {
            System.out.println(errorRecord(pdf, t));
        }
    }

    /**
     * {@code synthetic} is the one flag in this class that can change what leaves the process. When
     * {@code false} (every real-corpus caller, and the default from {@code main()} with no flag), the
     * output is byte-for-byte what this probe has always emitted -- no {@code observationSource}, no
     * {@code transactions}. Only an explicit {@code --synthetic} unlocks per-row content, and only
     * for a committed, reviewed fixture that has one: see
     * {@code scripts/ground-truth-match.py}'s {@code _observation_source()}, which treats an absent
     * marker as {@code REAL_CORPUS} -- the safe direction -- so a probe that forgets to pass this
     * flag loses capability rather than gaining permission to leak real transaction content.
     */
    static String probe(Path pdf, boolean synthetic) throws Exception {
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
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());

        var generated = generator.generateSectionsWithContext(
                UUID.randomUUID(), pdf.getFileName().toString(), bytes, null);

        List<StagedAccountSection> sections = generated.sections();
        int rows = 0;
        List<String> banks = new ArrayList<>();
        List<Section> detail = new ArrayList<>();
        Map<String, String> verification = new LinkedHashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            StagedAccountSection section = sections.get(i);
            int sectionRows = section.rows().size();
            rows += sectionRows;
            var account = section.detectedAccount();
            if (account != null && account.bank() != null) banks.add(account.bank().id());

            Map<String, String> sectionVerification = new LinkedHashMap<>();
            var report = section.verification();
            if (report != null) {
                for (var finding : report.findings()) {
                    sectionVerification.merge(finding.rule(), finding.outcome(), CorpusProbe::worse);
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
            List<Map<String, String>> transactions = synthetic ? transactionsOf(section) : null;
            detail.add(new Section(i, sectionRows,
                    account == null ? null : account.detectedProduct(),
                    account == null ? null : account.suggestedAccountType(),
                    account == null ? null : account.accountNumberMasked(),
                    account == null ? 0.0 : account.productConfidence(),
                    account != null && account.productNeedsReview(),
                    sectionVerification,
                    account == null ? null : account.openingBalance(),
                    account == null ? null : account.closingBalance(),
                    account == null ? null : account.statementPeriodStart(),
                    account == null ? null : account.statementPeriodEnd(),
                    account == null ? null : account.creditLimit(),
                    account == null ? null : account.totalAmountDue(),
                    account == null ? null : account.paymentDueDate(),
                    transactions));
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
         .append(synthetic ? "\"observationSource\":\"SYNTHETIC\"," : "")
         .append("\"pages\":").append(pages)
         .append(",\"extractedChars\":").append(extractedChars)
         .append(",\"positionedRuns\":").append(positionedRuns)
         .append(",\"sections\":").append(sections.size())
         .append(",\"rows\":").append(rows)
         .append(",\"layoutFingerprint\":").append(quote(fingerprint))
         .append(",\"banks\":").append(stringArray(banks))
         .append(",\"capabilities\":").append(stringArray(capabilities))
         .append(",\"verification\":").append(stringMap(verification))
         .append(",\"sectionDetail\":").append(sectionsJson(detail))
         .append('}')
         .append(",\"derived\":{")
         .append("\"documentClassification\":\"").append(DocumentClassification.of(signals)).append('"')
         .append(",\"suspectedIncompleteByPageRatio\":").append(signals.suspectedIncompleteByPageRatio())
         .append('}')
         .append('}');
        return j.toString();
    }

    /**
     * The per-row content {@code ground-truth-match.py}'s {@code VALUE_DIMENSIONS} axis compares --
     * only ever built when the caller has already committed to {@code --synthetic}. {@code type} is
     * this pipeline's INCOME/EXPENSE convention; the matcher's vocabulary (and
     * {@code GroundTruthDocument.row()}, its Java-side counterpart) is CREDIT/DEBIT, so this is the
     * one place that translation happens.
     */
    private static List<Map<String, String>> transactionsOf(StagedAccountSection section) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (var row : section.rows()) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("date", row.date() == null ? null : row.date().toString());
            r.put("description", row.description());
            r.put("amount", row.amount() == null ? null : row.amount().toPlainString());
            r.put("direction", "INCOME".equals(row.type()) ? "CREDIT" : "DEBIT");
            r.put("currency", "INR");
            rows.add(r);
        }
        return rows;
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
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions, TestAccountRepositories.anyLive()),
                TestRuleEngines.empty());
    }

    /**
     * One detected section, exactly as the pipeline reported it.
     *
     * <p>This is the authoritative structure in the record; the document-level {@code rows} and
     * {@code verification} beside it are conveniences for a summary line and must never be treated as
     * a substitute. Shivani_HDFC is the reason: three sections, 75 rows in the first and none in the
     * Recurring Deposit or Fixed Deposit ones, which at document level is indistinguishable from a
     * single-account statement that parsed fine.
     *
     * <p>Carries no account grouping and no section identity. The pipeline cannot say whether two
     * sections belong to one financial account -- {@code accountNumberMasked} is null in most of them
     * -- so this records what was observed and leaves that question to whoever can answer it.
     */
    record Section(int index, int rows, String detectedProduct, String suggestedAccountType,
                   String accountNumberMasked, double productConfidence, boolean productNeedsReview,
                   Map<String, String> verification,
                   BigDecimal openingBalance, BigDecimal closingBalance,
                   LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
                   BigDecimal creditLimit, BigDecimal totalAmountDue, LocalDate paymentDueDate,
                   /** Null on every real-corpus probe. Only {@code --synthetic} ever populates
                    *  this -- see {@code transactionsOf} and {@code probe}'s own doc comment. */
                   List<Map<String, String>> transactions) {}

    /**
     * Renders sections as an ordered JSON array.
     *
     * <p>Order is preserved and is load-bearing: rows moving BETWEEN sections while the total holds
     * constant is a real regression class -- an RD's transactions landing in the Savings account --
     * and it is invisible in any aggregate. A consumer diffing two records must be able to see
     * [75,15,0] become [15,75,0].
     *
     * <p>Deliberately emits no derived figures: no row vector, no count of row-bearing sections. Both
     * are computable from this array, and duplicating them here would create a second thing to keep
     * in step with it.
     */
    static String sectionsJson(List<Section> sections) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (i > 0) b.append(',');
            b.append("{\"index\":").append(s.index())
             .append(",\"rows\":").append(s.rows())
             .append(",\"detectedProduct\":").append(s.detectedProduct() == null ? "null" : quote(s.detectedProduct()))
             .append(",\"suggestedAccountType\":").append(s.suggestedAccountType() == null ? "null" : quote(s.suggestedAccountType()))
             .append(",\"accountNumberMasked\":").append(s.accountNumberMasked() == null ? "null" : quote(s.accountNumberMasked()))
             .append(",\"productConfidence\":").append(Math.round(s.productConfidence() * 1000) / 1000.0)
             .append(",\"productNeedsReview\":").append(s.productNeedsReview())
             .append(",\"verification\":").append(stringMap(s.verification()))
             .append(",\"openingBalance\":").append(s.openingBalance() == null ? "null" : quote(s.openingBalance().toPlainString()))
             .append(",\"closingBalance\":").append(s.closingBalance() == null ? "null" : quote(s.closingBalance().toPlainString()))
             .append(",\"statementPeriodStart\":").append(s.statementPeriodStart() == null ? "null" : quote(s.statementPeriodStart().toString()))
             .append(",\"statementPeriodEnd\":").append(s.statementPeriodEnd() == null ? "null" : quote(s.statementPeriodEnd().toString()))
             .append(",\"creditLimit\":").append(s.creditLimit() == null ? "null" : quote(s.creditLimit().toPlainString()))
             .append(",\"totalAmountDue\":").append(s.totalAmountDue() == null ? "null" : quote(s.totalAmountDue().toPlainString()))
             .append(",\"paymentDueDate\":").append(s.paymentDueDate() == null ? "null" : quote(s.paymentDueDate().toString()))
             .append(s.transactions() == null ? "" : ",\"transactions\":" + transactionsJson(s.transactions()))
             .append('}');
        }
        return b.append(']').toString();
    }

    /** {@code null} means "not synthetic" and never reaches here -- {@link #sectionsJson} omits the
     *  key entirely in that case, matching {@code ground-truth-match.py}'s "absent means
     *  REAL_CORPUS" default rather than emitting a key that could be confused with "observed, and
     *  empty". */
    private static String transactionsJson(List<Map<String, String>> rows) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) b.append(',');
            Map<String, String> r = rows.get(i);
            b.append("{\"date\":").append(r.get("date") == null ? "null" : quote(r.get("date")))
             .append(",\"description\":").append(quote(r.get("description")))
             .append(",\"amount\":").append(r.get("amount") == null ? "null" : quote(r.get("amount")))
             .append(",\"direction\":").append(quote(r.get("direction")))
             .append(",\"currency\":").append(quote(r.get("currency")))
             .append('}');
        }
        return b.append(']').toString();
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
