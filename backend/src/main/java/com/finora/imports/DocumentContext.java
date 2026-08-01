package com.finora.imports;

import com.finora.dto.ImportDto.CapabilityActivation;
import com.finora.dto.ImportDto.FinancialDocumentMetadata;

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
    private int pages;
    private int tables;

    public DocumentContext(String sourceFormat, String parser) {
        this.sourceFormat = sourceFormat;
        this.parser = parser;
    }

    public void recordHeaders(List<String> headerNames) {
        for (String h : headerNames) {
            if (h != null && !h.isBlank() && !headers.contains(h)) headers.add(h);
        }
    }

    public void recordPages(int pageCount) {
        this.pages = pageCount;
    }

    public void recordTable() {
        this.tables++;
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
