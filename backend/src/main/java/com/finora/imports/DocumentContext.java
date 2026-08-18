package com.finora.imports;

import com.finora.dto.ImportDto.CapabilityActivation;
import com.finora.dto.ImportDto.FinancialDocumentMetadata;
import com.finora.imports.pdf.TextSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md): one
 * instance per parse run (per document, or per section for a multi-section PDF), threaded as an
 * explicit parameter through the pipeline stage that already knows a structural fact or fires a
 * capability, so it can record it once instead of that fact being computed and immediately
 * discarded the way running-balance and header signatures already were before this class existed.
 *
 * Deliberately format-agnostic (no "Pdf" in the name) -- both the PDF and CSV pipelines use the
 * same type, since the facts recorded here (pages/tables/columns/headers, capability names) apply
 * to either.  Deliberately NOT a confidence/scoring engine -- capabilities are recorded as
 * fire-or-don't-fire facts ({@code status} is always "SUCCESS" today), so this class only ever
 * grows new recorded facts, never derived judgments; that stays out of scope until Phase 5's own
 * entry criteria are actually met.
 */
public class DocumentContext {

    private final String sourceFormat;
    private final String parser;
    private final List<String> headers = new ArrayList<>();
    private final List<CapabilityActivation> capabilities = new ArrayList<>();
    private final List<String> diagnostics = new java.util.ArrayList<>();
    private int pages;
    // Null until acquisition records one -- see recordExtractedRuns.
    private Integer extractedRuns;
    // Null until acquisition records one, same optionality as extractedRuns -- the CSV path never
    // constructs an AcquiredDocument, so it never calls recordTextSource, and null there means
    // "not applicable", not "unknown".
    private TextSource textSource;
    private int tables;
    // What failed to parse, as a reason/shape histogram -- a fact of this parse run exactly like
    // the header list or the capability activations, and recorded the same way. Never the rows
    // themselves: see UnparseableRowSummary.
    private UnparseableRowSummary unparseable;

    /**
     * How many rows failed to become transaction anchors, per reason.
     *
     * <p>A histogram, not a set, and the distinction is the whole point. {@link #record} has set
     * semantics, so a single occurrence of a reason anywhere in a 2500-line document lights the
     * same marker as two thousand occurrences — which makes "was this a geometry problem or a
     * format problem" unanswerable. Both reasons appear in every real statement measured,
     * including ones that parse perfectly; only the proportion says where the fault is.
     */
    private final java.util.Map<String, Integer> unanchoredReasons = new java.util.LinkedHashMap<>();

    public DocumentContext(String sourceFormat, String parser) {
        this.sourceFormat = sourceFormat;
        this.parser = parser;
    }

    public void recordHeaders(List<String> headerNames) {
        for (String h : headerNames) {
            if (h != null && !h.isBlank() && !headers.contains(h)) headers.add(h);
        }
    }

    /**
     * How many text runs acquisition produced for the whole document.
     *
     * <p>A COUNT, never the text. It exists so a later stage can tell "we read nothing because the
     * pages carry no text" from "we read plenty and could not make a table of it" -- two failures
     * that need completely different things from the user and were previously indistinguishable to
     * them. Both produce zero transactions, so the distinction is not recoverable downstream and
     * has to be carried from where it is known.
     *
     * <p>Deliberately not a threshold or a judgement. Zero is a fact about the document; anything
     * above zero is left entirely to the existing rules, because no corpus evidence supports a
     * non-zero cutoff -- the lowest density measured parses 58 rows correctly.
     */
    public void recordExtractedRuns(int runCount) {
        this.extractedRuns = runCount;
    }

    /** Null when nothing recorded it, which is not the same as zero -- see hasNoExtractableText. */
    public Integer extractedRuns() {
        return extractedRuns;
    }

    /**
     * How this document's text was obtained -- native extraction, OCR, or both. A provenance
     * FACT, same as {@link #recordExtractedRuns}, not a judgement: {@code TextSource}'s own doc
     * comment is explicit that acquisition supplies evidence, it does not decide whether that
     * evidence is trustworthy. Recorded here (rather than only ever a local variable at the
     * acquisition call site) so it survives to the point {@code ImportVerifier.verify()} runs,
     * which is not the same call frame.
     */
    public void recordTextSource(TextSource source) {
        this.textSource = source;
    }

    /** Null when nothing recorded it -- the CSV path, or a call site that predates this field. */
    public TextSource textSource() {
        return textSource;
    }

    /**
     * True only when acquisition ran and found no text at all.
     *
     * <p>Returns false when the count was never recorded. That is the safe direction: an unrecorded
     * count means nobody looked, and "nobody looked" must not present itself as "we looked and the
     * document is an image".
     */
    public boolean hasNoExtractableText() {
        return extractedRuns != null && extractedRuns == 0;
    }

    public void recordPages(int pageCount) {
        this.pages = pageCount;
    }

    public void recordTable() {
        this.tables++;
    }

    public void recordUnparseable(java.util.List<com.finora.dto.ImportDto.UnparseableRow> rows) {
        this.unparseable = UnparseableRowSummary.of(rows);
    }

    public UnparseableRowSummary unparseable() {
        return unparseable;
    }

    public void recordTables(int tableCount) {
        this.tables = tableCount;
    }

    /** Records that {@code capabilityName} fired during this parse run -- see the Capability
     *  Registry in docs/engineering/financial-document-intelligence-principles.md for the full
     *  list. Safe to call more than once per run for the same capability (e.g. once per row); only
     *  the first activation is kept, since "did this fire at all" is the fact being recorded, not
     *  a count. */
    public void record(String capabilityName) {
        for (CapabilityActivation existing : capabilities) {
            if (existing.capability().equals(capabilityName)) return;
        }
        capabilities.add(new CapabilityActivation(capabilityName, "SUCCESS"));
    }

    public List<CapabilityActivation> capabilities() {
        return List.copyOf(capabilities);
    }

    /**
     * Records a diagnostic -- something observed about parse QUALITY, as opposed to a capability,
     * which is something the engine successfully DID.
     *
     * <p>A separate channel because collapsing the two makes the coverage figure measure the wrong
     * thing. {@code UNANCHORED_ROWS_ABANDONED} was recorded as a capability, and a capability count
     * that rises when the parser abandons more rows is a metric that improves as the engine gets
     * worse. The distinction, stated once so it stays stable:
     *
     * <ul>
     *   <li><b>Capability</b> -- a parser behaviour that improves extraction. RIGHT_ALIGNED_AMOUNTS,
     *       REPEATED_HEADER, PRINTED_SUMMARY_TOTALS.</li>
     *   <li><b>Diagnostic</b> -- a measurement explaining parse quality, good or bad.
     *       UNANCHORED_ROWS_ABANDONED.</li>
     * </ul>
     *
     * <p>Neither feeds the layout fingerprint -- see LAYOUT_FINGERPRINT_VERSION's spec, which hashes
     * the header set and nothing about the rows -- so this separation changes no stored fingerprint.
     *
     * <p>Same set semantics as {@link #record}: the fact being kept is "did this happen", not how
     * often. {@link #unanchoredReasons} is what answers "which fault dominates".
     */
    public void recordDiagnostic(String diagnosticName) {
        if (!diagnostics.contains(diagnosticName)) diagnostics.add(diagnosticName);
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /** Counts one row that could not be anchored, by reason. See {@link #unanchoredReasons}. */
    public void recordUnanchored(String reason) {
        unanchoredReasons.merge(reason, 1, Integer::sum);
    }

    /** Reason to count. Empty when every row anchored, which is the healthy case. */
    public java.util.Map<String, Integer> unanchoredReasons() {
        return java.util.Map.copyOf(unanchoredReasons);
    }

    public FinancialDocumentMetadata buildMetadata() {
        Set<String> recognized = TransactionNormalizer.recognizedColumnNames();
        List<String> unknownHeaders = new ArrayList<>();
        for (String header : headers) {
            if (!recognized.contains(CsvParser.normalizeHeaderCell(header))) unknownHeaders.add(header);
        }
        return new FinancialDocumentMetadata(sourceFormat, parser, pages, tables, headers.size(),
                List.copyOf(headers), unknownHeaders);
    }

    // The fingerprint is a deterministic LOOKUP KEY, not a permanent identity -- "FP-<version>-
    // <hash>" deliberately encodes which SPECIFICATION produced it (not just which hash function --
    // a future version may change the canonical inputs, the hash algorithm, or both). Bump this
    // constant, never silently change what buildFingerprint() computes under the same version
    // number -- without that discipline, an old and a new fingerprint for the SAME real layout
    // would look identical in format but never compare equal, and there'd be no way to tell which
    // rows in layout_fingerprint need re-deriving after a spec change. This is the seam a future
    // LayoutProfile (a canonical record mapping one display name to one or more fingerprint
    // versions) would sit on top of -- not built yet, no evidence it's needed (see "Evidence
    // Before Capability"), but the version prefix is what makes that mapping possible later
    // without having to migrate every already-stored fingerprint.
    //
    // Version 1 spec -- what changing this number means signing up to re-derive:
    //   Inputs:  sourceFormat ("PDF"/"CSV") + header COUNT + the SET of normalized header names
    //            (CsvParser.normalizeHeaderCell'd, deduplicated, sorted alphabetically).
    //   Excludes: header/column ORDER, x-position/spacing, page count, table count, parser name,
    //             and anything about the data rows themselves -- none of it feeds the hash. Two
    //             documents with the same header set in a different order, or the same headers at
    //             different x-coordinates, produce the SAME v1 fingerprint on purpose (see
    //             buildFingerprint()'s own doc comment for why order-independence is intentional).
    //   Hash:    SHA-256 of "{sourceFormat}|{headerCount}|{sortedNormalizedHeaders joined by ,}",
    //            first 8 hex characters, uppercased.
    // A v2 that incorporates column x-positions/spacing, or narrows/widens what counts as "the same
    // layout" in any other way, is a new spec -- increment here, document the new spec above this
    // line (don't overwrite what v1 meant), and leave already-stored "FP-1-..." values as-is.
    private static final int LAYOUT_FINGERPRINT_VERSION = 1;

    /** Deterministic layout ID: the same sourceFormat + column count + normalized header set
     *  always hashes to the same "FP-{@link #LAYOUT_FINGERPRINT_VERSION}-XXXXXXXX" string, so
     *  "have we seen this layout before" is an equality check against this string later, not a
     *  JSON diff against the full metadata. Header order doesn't affect the fingerprint (a sorted
     *  set is hashed, not the raw list) since the same logical layout can have header cells
     *  extracted in a slightly different order between runs (PDFBox text-run ordering) without
     *  actually being a different layout -- see {@link #LAYOUT_FINGERPRINT_VERSION}'s own doc
     *  comment for the exact, versioned spec of what feeds this hash. */
    public String buildFingerprint() {
        Set<String> normalizedHeaders = new LinkedHashSet<>();
        for (String h : headers) normalizedHeaders.add(CsvParser.normalizeHeaderCell(h));
        List<String> sorted = new ArrayList<>(normalizedHeaders);
        sorted.sort(String::compareTo);

        String canonical = sourceFormat + "|" + headers.size() + "|" + String.join(",", sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return "FP-" + LAYOUT_FINGERPRINT_VERSION + "-" + hex.substring(0, 8).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
